package dev.hkgill.gillspeak

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
 *
 * Each dictation is tied to the field it started in ([Ui.field]). If the field has changed by the time the text
 * is ready, it is not inserted anywhere: it's kept, and the user can tap to put it in the current field.
 */
class MicController(private val context: Context, private val ui: Ui) {
    enum class State { IDLE, RECORDING, LATCHED, WORKING }

    interface Ui {
        fun render(state: State)
        fun status(text: String, opensApp: Boolean = false)
        fun insert(text: String)

        /** Identifies the text field in focus (compared with ==), or null when there is none. */
        fun field(): Any?

        /** Why dictation isn't allowed in the current field (a password field), or null when it is. */
        fun blocked(): String?
    }

    val settings = Settings(context)
    private val recorder = Recorder()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    var state = State.IDLE
        private set
    private var pressedAt = 0L
    private var recordingSince = 0L
    private var job = 0 // bumped on every dictation (and on shutdown) so a stale result is ignored
    private var session: Any? = null // the field this dictation belongs to
    private var failedAudio: ByteArray? = null
    private var pendingText: String? = null // finished, but the field changed before it could be inserted
    private var lastWarm = 0L

    /** A tap now retries a failed request, or inserts text that couldn't be delivered. */
    val canRetry get() = (failedAudio != null || pendingText != null) && state == State.IDLE
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

    /** Discards a recording in progress, like `gillspeak cancel`. A null message cancels quietly. */
    fun cancel(message: String? = "Cancelled") {
        if (state != State.RECORDING && state != State.LATCHED) return
        Log.i(Dictation.TAG, "recording cancelled, peak=${recorder.peak}")
        recorder.cancel()
        set(State.IDLE)
        message?.let { ui.status(it) }
    }

    /** Inserts undelivered text into the current field, or retries a failed request for it. */
    fun retry() {
        if (state != State.IDLE) return
        ui.blocked()?.let { return ui.status(it) }
        pendingText?.let {
            pendingText = null
            ui.insert(it)
            ui.status("Inserted")
            return
        }
        failedAudio?.let {
            session = ui.field()
            process(it)
        }
    }

    /** True when tapping should open the app to finish setup. Checked fresh each time, never remembered. */
    fun needsSetup() = idleHint().second

    /** Opens the TLS connection or loads the local model ahead of time, at most once a minute. */
    fun warm() {
        val now = SystemClock.uptimeMillis()
        if (settings.engineProblem(context) != null || now - lastWarm < 60_000) return
        lastWarm = now
        when (settings.engine) {
            // Loading the on-device model takes a few seconds; do it while the keyboard is up, not after speaking.
            Settings.ENGINE_LOCAL -> worker.execute { runCatching { LocalAsr.warm(context) } }
            Settings.ENGINE_GEMINI -> Gemini(settings.apiKey, settings.model, "").let { worker.execute { it.warm() } }
        }
    }

    fun shutdown() {
        job++ // a result still in flight must not be delivered anywhere
        recorder.cancel()
        worker.shutdownNow()
    }

    /** What to show when nothing is happening, and whether tapping it should open the app. */
    fun idleHint(): Pair<String, Boolean> {
        if (!hasMicPermission()) return "Tap here to allow the microphone" to true
        settings.engineProblem(context)?.let { return it to true }
        return Settings.APP_NAME to false
    }

    private fun start(): Boolean {
        val (hint, opensApp) = idleHint()
        if (opensApp) {
            ui.status(hint, opensApp = true)
            return false
        }
        ui.blocked()?.let {
            ui.status(it)
            return false
        }
        try {
            recorder.start()
        } catch (e: Exception) {
            ui.status("Microphone unavailable: ${e.message}")
            return false
        }
        failedAudio = null
        pendingText = null
        session = ui.field()
        recordingSince = SystemClock.uptimeMillis()
        set(State.RECORDING)
        tick()
        return true
    }

    private fun tick() {
        if (state != State.RECORDING && state != State.LATCHED) return
        if (!recorder.isRecording) { // hit the 5-minute cap, or the microphone failed
            finish()
            return
        }
        val s = (SystemClock.uptimeMillis() - recordingSince) / 1000
        ui.status("%d:%02d".format(s / 60, s % 60))
        main.postDelayed(::tick, 250)
    }

    private fun finish() {
        val peak = recorder.peak
        val error = recorder.error
        val wav = recorder.stop()
        val ms = Recorder.durationMs(wav)
        Log.i(Dictation.TAG, "recording stopped: $ms ms, peak=$peak${error?.let { ", microphone error $it" }.orEmpty()}")
        if (error != null) settings.log("Microphone error $error after $ms ms; using what was recorded")
        when {
            ms < MIN_MS -> {
                set(State.IDLE)
                ui.status(if (error != null) "The microphone stopped working" else "Too short. Hold the mic while you speak")
            }
            // Android hands background apps pure silence instead of an error. Never send that anywhere.
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
        val forField = session
        set(State.WORKING)
        ui.status("Transcribing…")
        worker.execute {
            val result = runCatching { Dictation.run(context, settings, wav) }
            main.post {
                if (id != job) return@post
                set(State.IDLE)
                result.fold(
                    onSuccess = { out -> deliver(out, forField) },
                    onFailure = { e ->
                        failedAudio = wav
                        val kind = Dictation.logFailure(settings, e)
                        ui.status(if (kind == "rate_limit") "Rate limit reached. Tap to retry" else "Failed ($kind). Tap to retry")
                    },
                )
            }
        }
    }

    private fun deliver(out: Dictation.Outcome, forField: Any?) {
        failedAudio = null
        val here = ui.field()
        val blocked = ui.blocked()
        when {
            out.text.isBlank() -> ui.status("No speech heard")
            // Moved to another field (or it closed) while this was transcribing: never put it somewhere the user
            // didn't dictate. Keep it for an explicit tap instead.
            here == null || here != forField -> {
                pendingText = out.text
                ui.status("The field changed. Tap to insert it here")
            }
            blocked != null -> ui.status(blocked)
            else -> {
                ui.insert(out.text)
                ui.status("%.1f s%s".format(out.ms / 1000.0, if (out.reason.isEmpty()) "" else " · rules only (${out.reason})"))
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
