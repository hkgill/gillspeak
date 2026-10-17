package dev.hkgill.gillspeak

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 kHz mono PCM16 into memory. Route setup and capture run off the UI thread. */
class Recorder internal constructor(
    private val platform: () -> RoutingPlatform,
    private val clock: RouteClock,
    private val createCapture: (RoutingPlatform, Boolean) -> MicCapture,
) {
    constructor(context: Context) : this(
        { AndroidMicrophoneRouting(context.applicationContext) }, AndroidRouteClock,
        { routing, wireless -> AndroidMicCapture(routing as AndroidMicrophoneRouting, wireless) },
    )
    private class Session {
        val pcm = ByteArrayOutputStream()
        @Volatile var cancelled = false
        @Volatile var running = true
        @Volatile var preparing = true
        @Volatile var capture: MicCapture? = null
        lateinit var thread: Thread
    }
    private var session: Session? = null

    @Volatile var peak = 0
        private set
    @Volatile var level = 0f
        private set
    @Volatile var error: Int? = null
        private set
    @Volatile var failure: String? = null
        private set
    @Volatile var routeStatus = "Checking microphone…"
        private set
    @Volatile var startedAt = 0L
        private set
    @Volatile private var chunks = 0
    @Volatile private var zeroChunks = 0

    val blocked get() = mostlyZero(chunks, zeroChunks)
    val heard get() = chunks > 0
    val isRecording get() = session?.running == true
    val preparing get() = session?.preparing == true

    /** Caller checks RECORD_AUDIO. Multiple activation surfaces must never own routing simultaneously. */
    fun start() {
        check(session?.thread?.isAlive != true) { "microphone is still stopping" }
        val s = Session()
        check(RecordingOwner.acquire(s)) { "microphone is already in use by GillSpeak" }
        peak = 0
        level = 0f
        error = null
        failure = null
        chunks = 0
        zeroChunks = 0
        startedAt = 0L
        routeStatus = "Connecting microphone…"
        session = s
        s.thread = Thread({ capture(s) }, "gillspeak-recorder")
        try { s.thread.start() } catch (e: Exception) {
            s.running = false
            RecordingOwner.release(s)
            throw e
        }
    }

    private fun capture(s: Session) {
        var owned: RoutedCapture? = null
        try {
            val backend = platform()
            val routed = RoutedCapture(backend, clock) { wireless ->
                createCapture(backend, wireless).also { s.capture = it }
            }
            owned = routed
            routed.open { s.cancelled }
            if (s.cancelled) return
            val c = checkNotNull(routed.capture)
            routeStatus = microphoneStatus(c.actual(), routed.choice.reason)
            startedAt = clock.now()
            s.preparing = false
            val buf = ByteArray(1600) // 50 ms
            while (!s.cancelled) {
                val n = c.read(buf)
                if (s.cancelled) break
                if (n < 0) {
                    error = n
                    failure = "Microphone stopped working. Check the headset and try again"
                    break
                }
                // Read actual routing after capture starts, and throughout capture (including disconnects).
                val actual = c.actual()
                if (routed.routeLost(actual)) {
                    failure = "Microphone disconnected or changed. Tap to record again"
                    break
                }
                routeStatus = microphoneStatus(actual, routed.choice.reason)
                if (n == 0) continue
                var chunk = 0
                for (i in 0 until n - 1 step 2) chunk = maxOf(chunk, kotlin.math.abs((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)))
                peak = maxOf(peak, chunk)
                chunks++
                if (chunk == 0) zeroChunks++
                level = (chunk / 12_000f).coerceAtMost(1f)
                synchronized(s.pcm) {
                    s.pcm.write(buf, 0, n)
                }
                if (s.pcm.size() >= MAX_BYTES) break
            }
        } catch (_: RouteCancelled) {
            // A release, cancellation or service shutdown during asynchronous route setup.
        } catch (_: SecurityException) {
            failure = "Microphone permission denied. Allow it in GillSpeak settings"
        } catch (_: Exception) {
            if (!s.cancelled) failure = "Microphone unavailable. Check the headset or try again"
        } finally {
            try { owned?.close() } finally {
                s.capture = null
                s.preparing = false
                RecordingOwner.release(s)
                s.running = false
            }
        }
    }

    fun stop(): ByteArray {
        val s = halt()
        val data = s?.let { synchronized(it.pcm) { it.pcm.toByteArray().also { _ -> it.pcm.reset() } } }
            ?: byteArrayOf()
        return wav(data)
    }

    fun cancel() {
        halt()?.let { synchronized(it.pcm) { it.pcm.reset() } }
    }

    private fun halt(): Session? {
        val s = session ?: return null
        s.cancelled = true
        // AudioRecord.stop unblocks a blocking read. Only the capture thread releases native resources/routes.
        runCatching { s.capture?.stop() }
        s.thread.join(1000)
        return s
    }

    companion object {
        const val RATE = 16_000
        const val MAX_BYTES = RATE * 2 * 300 // 5 minutes

        /** At least 300 ms read, and 80% or more of it all zeros. */
        fun mostlyZero(chunks: Int, zeroChunks: Int) = chunks >= 6 && zeroChunks * 10 >= chunks * 8

        fun durationMs(wav: ByteArray) = ((wav.size - 44).coerceAtLeast(0) * 1000L) / (RATE * 2)

        fun wav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
    }
}

/** One lease across the bubble, keyboard and side-button controllers. */
internal object RecordingOwner {
    private var owner: Any? = null
    @Synchronized fun acquire(token: Any): Boolean {
        if (owner != null) return false
        owner = token
        return true
    }
    @Synchronized fun release(token: Any) { if (owner === token) owner = null }
}

private class AndroidMicCapture(platform: AndroidMicrophoneRouting, wireless: Boolean) : MicCapture {
    private val routing = platform
    private val communication = wireless
    private var rate = Recorder.RATE
    private val narrowband = ByteArray(800)
    private val upsample = NarrowbandPcm()
    private var record = try {
        openRecord()
    } catch (e: IllegalStateException) {
        if (!communication) throw e
        rate = 8_000
        openRecord()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun openRecord() = AudioRecord(
        if (communication) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION,
        rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), rate),
    ).also {
        if (it.state != AudioRecord.STATE_INITIALIZED) {
            it.release()
            throw IllegalStateException("microphone unavailable")
        }
    }

    override fun prefer(device: MicDevice?): Boolean {
        val input = device?.let { routing.input(it.id) }
        if (device != null && input == null) return false
        return record.setPreferredDevice(input)
    }
    @android.annotation.SuppressLint("MissingPermission")
    @Synchronized override fun start() {
        try {
            startChecked()
        } catch (e: IllegalStateException) {
            if (!communication || rate == 8_000) throw e
            // Some classic SCO implementations accept only narrowband input. Keep ASR's format at 16 kHz.
            record.release()
            rate = 8_000
            record = openRecord()
            startChecked()
        }
    }
    @android.annotation.SuppressLint("MissingPermission")
    private fun startChecked() {
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "microphone did not start" }
    }
    override fun actual() = record.routedDevice?.micDevice()
    override fun read(buffer: ByteArray): Int {
        if (rate == Recorder.RATE) return record.read(buffer, 0, buffer.size)
        val n = record.read(narrowband, 0, minOf(narrowband.size, buffer.size / 2))
        return if (n <= 0) n else upsample.expand(narrowband, n, buffer)
    }
    @Synchronized override fun stop() { runCatching { record.stop() } }
    @Synchronized override fun close() = record.release()
}
