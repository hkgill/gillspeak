package dev.hkgill.murmur

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

/**
 * The mic state machine shared by the keyboard and the floating bubble: press and hold to talk, release to
 * finish, or press briefly to latch and press again to finish. Runs the pipeline off the main thread and hands
 * the result to [Ui.insert].
 */
class MicController(private val context: Context, private val ui: Ui) {
    enum class State { IDLE, RECORDING, LATCHED, WORKING }

    interface Ui {
        fun render(state: State)
        fun status(text: String, opensApp: Boolean = false)
        fun insert(text: String)
    }

    val settings = Settings(context)
    private val recorder = Recorder()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val systemPrompt by lazy { settings.systemPrompt() }

    var state = State.IDLE
        private set
    private var pressedAt = 0L
    private var recordingSince = 0L
    private var job = 0 // bumped on every dictation so a stale result is ignored
    private var failedAudio: ByteArray? = null
    private var lastWarm = 0L

    val canRetry get() = failedAudio != null && state == State.IDLE
    val level get() = recorder.level
    val isActive get() = state != State.IDLE

    /** Finger down on the mic. Returns true when something started or finished (worth a haptic tick). */
    fun press(): Boolean = when (state) {
        State.IDLE -> start().also { if (it) pressedAt = SystemClock.uptimeMillis() }
        State.LATCHED -> { finish(); true }
        else -> false
    }

    /** Finger up. A short press latches recording on; a long one finishes it. Returns true when it finished. */
    fun release(): Boolean {
        if (state != State.RECORDING) return false
        if (SystemClock.uptimeMillis() - pressedAt < TAP_MS) {
            set(State.LATCHED)
            return false
        }
        finish()
        return true
    }

    /** Discards a recording in progress, like `murmur cancel`. A null message cancels quietly. */
    fun cancel(message: String? = "Cancelled") {
        if (state != State.RECORDING && state != State.LATCHED) return
        Log.i(Dictation.TAG, "recording cancelled, peak=${recorder.peak}")
        recorder.cancel()
        set(State.IDLE)
        message?.let { ui.status(it) }
    }

    /** Forgets audio kept for a retry, e.g. when the field it was meant for is gone. */
    fun discardFailed() {
        failedAudio = null
    }

    fun retry() {
        failedAudio?.takeIf { state == State.IDLE }?.let(::process)
    }

    /** Opens the TLS connection ahead of time, at most once a minute. */
    fun warm() {
        val now = SystemClock.uptimeMillis()
        if (settings.apiKey.isBlank() || now - lastWarm < 60_000) return
        lastWarm = now
        val gemini = Gemini(settings.apiKey, settings.model, "")
        worker.execute { gemini.warm() }
    }

    fun shutdown() {
        recorder.cancel()
        worker.shutdownNow()
    }

    /** What to show when nothing is happening, and whether tapping it should open the app. */
    fun idleHint(): Pair<String, Boolean> = when {
        !hasMicPermission() -> "Tap here to allow the microphone" to true
        settings.apiKey.isBlank() -> "Tap here to add a Gemini API key" to true
        else -> Settings.APP_NAME to false
    }

    private fun start(): Boolean {
        val (hint, opensApp) = idleHint()
        if (opensApp) {
            ui.status(hint, opensApp = true)
            return false
        }
        try {
            recorder.start()
        } catch (e: Exception) {
            ui.status("Microphone unavailable: ${e.message}")
            return false
        }
        failedAudio = null
        recordingSince = SystemClock.uptimeMillis()
        set(State.RECORDING)
        tick()
        return true
    }

    private fun tick() {
        if (state != State.RECORDING && state != State.LATCHED) return
        if (!recorder.isRecording) { // hit the 5-minute cap
            finish()
            return
        }
        val s = (SystemClock.uptimeMillis() - recordingSince) / 1000
        ui.status("%d:%02d".format(s / 60, s % 60))
        main.postDelayed(::tick, 250)
    }

    private fun finish() {
        val peak = recorder.peak
        val wav = recorder.stop()
        val ms = Recorder.durationMs(wav)
        Log.i(Dictation.TAG, "recording stopped: ${ms} ms, peak=$peak")
        when {
            ms < MIN_MS -> {
                set(State.IDLE)
                ui.status("Too short. Hold the mic while you speak")
            }
            // Android hands background apps pure silence instead of an error. Never send that to Gemini.
            peak == 0 -> {
                set(State.IDLE)
                settings.log("FAILED silent: the microphone returned only silence (blocked in the background?)")
                ui.status("The microphone was blocked (silent audio)")
            }
            else -> process(wav)
        }
    }

    private fun process(wav: ByteArray) {
        val id = ++job
        set(State.WORKING)
        ui.status("Transcribing…")
        worker.execute {
            val result = runCatching { Dictation.run(settings, systemPrompt, wav) }
            main.post {
                if (id != job) return@post
                set(State.IDLE)
                result.fold(
                    onSuccess = { out ->
                        if (out.text.isBlank()) {
                            ui.status("No speech heard")
                        } else {
                            ui.insert(out.text)
                            ui.status("%.1f s%s".format(out.ms / 1000.0, if (out.reason.isEmpty()) "" else " · rules only (${out.reason})"))
                        }
                    },
                    onFailure = { e ->
                        failedAudio = wav
                        val kind = Dictation.logFailure(settings, e)
                        ui.status(if (kind == "rate_limit") "Gemini rate limit. Tap to retry" else "Failed ($kind). Tap to retry")
                    },
                )
            }
        }
    }

    private fun set(s: State) {
        state = s
        ui.render(s)
    }

    private fun hasMicPermission() =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAP_MS = 350L // a shorter press latches recording on
        private const val MIN_MS = 400L
    }
}
