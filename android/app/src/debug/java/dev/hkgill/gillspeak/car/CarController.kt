package dev.hkgill.gillspeak.car

import dev.hkgill.gillspeak.Recorder
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

internal enum class CarPhase { READY, LISTENING, PROCESSING, STOPPING, RESULT, ERROR, DISCONNECTED }

internal data class CarState(val phase: CarPhase, val message: String) {
    val canSpeak get() = phase in setOf(CarPhase.READY, CarPhase.RESULT, CarPhase.ERROR)
}

/** A blocking PCM source. stop() must unblock read(), and is safe before start or after stop. */
internal interface CarAudioInput {
    fun start()
    fun read(buffer: ByteArray): Int
    fun stop()
}

internal data class CarReply(val text: String, val detail: String)

internal interface CarInference {
    fun transcribe(wav: ByteArray): String
    fun polish(text: String, owner: Any, cancelled: () -> Boolean): CarReply
    fun cancel(owner: Any)
}

/**
 * All public events and UI delivery use the main executor. One worker owns the audio and inference;
 * cancellation never queues a replacement behind native work which may still be winding down.
 * No recorder, model load, download, or inference happens just by opening/reconnecting the screen.
 */
internal class CarController(
    private val worker: Executor,
    private val main: Executor,
    private val audio: (() -> Unit) -> CarAudioInput,
    private val inference: CarInference,
    private val problem: () -> String?,
    private val later: (Long, () -> Unit) -> (() -> Unit),
    private val changed: (CarState) -> Unit,
) {
    var state = CarState(CarPhase.READY, "Tap Speak to start. Works offline with installed models.")
        private set
    private var connected = true
    private var active: Run? = null

    private class Run {
        @Volatile var cancelled = false
        @Volatile var finished = false
        var source: CarAudioInput? = null // protected by this Run's monitor
        var removeTimer: () -> Unit = {}
    }

    fun speak() {
        if (!connected || active != null) return
        problem()?.let { show(CarPhase.ERROR, it); return }
        val run = Run()
        active = run
        show(CarPhase.LISTENING, "Listening · tap Finish or Cancel. 15 seconds maximum.")
        run.removeTimer = later(15_000) { if (active === run) finish() }
        worker.execute {
            try {
                val wav = capture(run)
                if (run.cancelled) return@execute
                main.execute {
                    if (active === run && !run.cancelled) {
                        run.removeTimer()
                        run.removeTimer = later(60_000) {
                            if (active === run) cancel("Timed out. Tap Speak to try again once stopped.")
                        }
                        show(CarPhase.PROCESSING, "Processing on the phone · Cancel to discard.")
                    }
                }
                if (run.cancelled) return@execute
                val text = inference.transcribe(wav)
                if (run.cancelled) return@execute
                if (text.isBlank()) throw CaptureFailure("No speech heard. Tap Speak to try again.")
                val reply = inference.polish(text, run) { run.cancelled }
                main.execute {
                    if (active === run && !run.cancelled && connected) {
                        show(CarPhase.RESULT, "${reply.text.take(160)}\n${reply.detail}")
                    }
                }
            } catch (e: Exception) {
                main.execute {
                    if (active === run && !run.cancelled && connected) {
                        // Never display exception messages from model/host code (they may contain private data).
                        val message = if (e is CaptureFailure) e.message.orEmpty()
                            else "Local processing unavailable. Check models on the phone when parked."
                        show(CarPhase.ERROR, message)
                    }
                }
            } finally {
                synchronized(run) { run.source?.stop(); run.source = null }
                main.execute {
                    if (active === run) {
                        run.removeTimer()
                        active = null
                        if (connected && run.cancelled) show(CarPhase.READY, "Discarded. Tap Speak to start again.")
                    }
                }
            }
        }
    }

    fun finish() {
        val run = active ?: return
        if (state.phase != CarPhase.LISTENING) return
        synchronized(run) {
            run.finished = true
            run.source?.stop()
        }
    }

    fun cancel(message: String? = null) {
        val run = active
        if (run != null) {
            synchronized(run) {
                run.cancelled = true
                run.source?.stop()
            }
            run.removeTimer()
            inference.cancel(run)
        }
        show(if (run == null) CarPhase.READY else CarPhase.STOPPING,
            message ?: if (run == null) "Cleared. Tap Speak to start again." else "Discarding. Waiting for local work to stop.")
    }

    fun disconnect() {
        connected = false
        cancel()
        show(CarPhase.DISCONNECTED, "Disconnected. Audio and results discarded.")
    }

    fun reconnect() {
        connected = true
        show(if (active == null) CarPhase.READY else CarPhase.STOPPING,
            "Reconnected. Tap Speak after local work has stopped.")
    }

    private fun capture(run: Run): ByteArray {
        val source = audio {
            main.execute {
                if (active === run) cancel("Audio focus lost. Recording discarded.")
            }
        }
        synchronized(run) {
            run.source = source
            if (run.cancelled || run.finished) throw CaptureFailure("Recording discarded.")
            source.start()
        }
        val pcm = ByteArrayOutputStream()
        val buffer = ByteArray(512) // CarAudioRecord's maximum read size
        var nonzero = false
        var emptyReads = 0
        while (!run.cancelled && !run.finished && pcm.size() < MAX_PCM_BYTES) {
            val n = try { source.read(buffer) } catch (e: Exception) {
                if (run.cancelled || run.finished) break else throw e
            }
            if (n < 0) {
                if (!run.finished) throw CaptureFailure("Microphone dismissed or interrupted. Recording discarded.")
                break
            }
            if (n == 0) {
                if (++emptyReads >= 64) throw CaptureFailure("Car microphone unavailable. Tap Speak to try again.")
                continue
            }
            if (n > buffer.size || n % 2 != 0) throw CaptureFailure("Unsupported car audio format.")
            emptyReads = 0
            nonzero = nonzero || (0 until n).any { buffer[it] != 0.toByte() }
            pcm.write(buffer, 0, minOf(n, MAX_PCM_BYTES - pcm.size()))
        }
        synchronized(run) { source.stop(); run.source = null }
        if (!nonzero || pcm.size() < 9_600) throw CaptureFailure("No usable audio. Check the microphone when parked.")
        return Recorder.wav(pcm.toByteArray())
    }

    private fun show(phase: CarPhase, message: String) {
        state = CarState(phase, message)
        changed(state)
    }

    private class CaptureFailure(message: String) : Exception(message)

    companion object { const val MAX_PCM_BYTES = 16_000 * 2 * 15 }
}
