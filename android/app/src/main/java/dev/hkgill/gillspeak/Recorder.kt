package dev.hkgill.gillspeak

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 kHz mono PCM16 into memory. Audio is never written to disk. */
class Recorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile private var running = false

    /** Loudest sample so far (0..32767). Stays 0 when Android silences a background recording. */
    @Volatile var peak = 0
        private set

    /** Loudness of the last 100 ms, 0..1, for the recording animation. */
    @Volatile var level = 0f
        private set

    /** The AudioRecord error that stopped the recording early (e.g. ERROR_DEAD_OBJECT), or null. */
    @Volatile var error: Int? = null
        private set

    @Volatile private var chunks = 0
    @Volatile private var zeroChunks = 0

    /**
     * Most of the recording was exact digital silence (every sample 0). A real microphone always picks up some
     * noise; Android sends zeros when another app holds the microphone, even if a tap made a loud click first.
     */
    val blocked get() = mostlyZero(chunks, zeroChunks)

    val isRecording get() = running

    /** Caller checks RECORD_AUDIO first. Throws if the microphone can't be opened. */
    @SuppressLint("MissingPermission")
    fun start() {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE),
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("microphone unavailable")
        }
        synchronized(pcm) { pcm.reset() }
        peak = 0
        level = 0f
        error = null
        chunks = 0
        zeroChunks = 0
        record = r
        running = true
        r.startRecording()
        thread = Thread({
            val buf = ByteArray(1600) // 50 ms, so stopping never waits long
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n < 0) {
                    // Errors don't clear on their own (a dead object needs a new AudioRecord), so retrying would
                    // spin forever. Stop; the controller finishes with what was recorded.
                    error = n
                    running = false
                    break
                }
                if (n == 0) continue
                var chunk = 0
                for (i in 0 until n - 1 step 2) chunk = maxOf(chunk, kotlin.math.abs((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)))
                peak = maxOf(peak, chunk)
                chunks++
                if (chunk == 0) zeroChunks++
                level = (chunk / 12_000f).coerceAtMost(1f) // normal speech peaks well below full scale
                synchronized(pcm) {
                    pcm.write(buf, 0, n)
                    if (pcm.size() >= MAX_BYTES) running = false
                }
            }
        }, "gillspeak-recorder").apply { start() }
    }

    /** Stops and returns the recording as a WAV file in memory. */
    fun stop(): ByteArray {
        halt()
        val data = synchronized(pcm) { pcm.toByteArray().also { pcm.reset() } }
        return wav(data)
    }

    fun cancel() {
        halt()
        synchronized(pcm) { pcm.reset() }
    }

    private fun halt() {
        running = false
        thread?.join(1000)
        thread = null
        record?.run { runCatching { stop() }; release() }
        record = null
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
