package dev.hkgill.murmur

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * A voice keyboard: hold the mic to talk and release to insert, or tap it to latch and tap again to finish.
 * Audio goes to Gemini, which returns a verbatim transcript and a cleaned version; the cleaned text is used
 * when the validator accepts it, otherwise the rules-cleaned transcript (as in Murmur's daemon).
 */
class MurmurIme : InputMethodService() {
    private enum class State { IDLE, RECORDING, LATCHED, WORKING }

    private lateinit var settings: Settings
    private val recorder = Recorder()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var state = State.IDLE
    private var pressedAt = 0L
    private var recordingSince = 0L
    private var job = 0 // bumped on every dictation so a stale result is ignored
    private var failedAudio: ByteArray? = null
    private var lastInserted: String? = null
    private var lastWarm = 0L
    private var statusOpensApp = false
    private val systemPrompt by lazy { settings.systemPrompt() }

    private lateinit var status: TextView
    private lateinit var mic: TextView
    private lateinit var colors: Palette

    private data class Palette(val bg: Int, val key: Int, val text: Int, val dim: Int, val idle: Int, val rec: Int, val busy: Int)

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
    }

    override fun onDestroy() {
        recorder.cancel()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (state == State.IDLE && failedAudio == null) showStatus(idleHint())
        warm()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // The keyboard went away mid-recording: discard, like `murmur cancel`.
        if (state == State.RECORDING || state == State.LATCHED) {
            recorder.cancel()
            state = State.IDLE
            render()
        }
        super.onFinishInputView(finishingInput)
    }

    // ---- UI ----

    override fun onCreateInputView(): View {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        colors = if (night) {
            Palette(0xFF1B1B1F.toInt(), 0xFF2E2E34.toInt(), Color.WHITE, 0xFFA0A0AA.toInt(), 0xFF3D7BFD.toInt(), 0xFFE5484D.toInt(), 0xFFD9901A.toInt())
        } else {
            Palette(0xFFE8E8ED.toInt(), Color.WHITE, 0xFF111114.toInt(), 0xFF5C5C66.toInt(), 0xFF2F6BF0.toInt(), 0xFFD93036.toInt(), 0xFFC7800F.toInt())
        }
        val pad = dp(6)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colors.bg)
            setPadding(pad, pad, pad, pad)
            // Keep the bottom row clear of the gesture/navigation bar (edge-to-edge on Android 15+).
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(pad, pad, pad, pad + insets.getInsets(WindowInsets.Type.navigationBars()).bottom)
                insets
            }
        }

        status = TextView(this).apply {
            setTextColor(colors.dim)
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 2
            setOnClickListener { onStatusTap() }
        }
        root.addView(row(dp(44),
            key("⌨") { switchKeyboard() } to dp(56),
            status to 0,
            key("↶") { undoLast() } to dp(56),
        ))

        mic = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(Color.WHITE)
            setOnTouchListener(::onMicTouch)
        }
        root.addView(row(dp(150), mic to 0))

        root.addView(row(dp(50),
            repeatingKey("⌫") { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) } to dp(80),
            key("space") { currentInputConnection?.commitText(" ", 1) } to 0,
            key("↵") { enter() } to dp(80),
        ))
        render()
        showStatus(idleHint())
        return root
    }

    private fun row(height: Int, vararg cells: Pair<View, Int>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
        for ((view, width) in cells) {
            addView(view, LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.MATCH_PARENT, if (width == 0) 1f else 0f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(color)
    }

    private fun key(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(colors.text)
        background = rounded(colors.key)
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun repeatingKey(label: String, action: () -> Unit) = key(label) {}.apply {
        val repeat = object : Runnable {
            override fun run() {
                action()
                main.postDelayed(this, 60)
            }
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    action()
                    main.postDelayed(repeat, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> main.removeCallbacks(repeat)
            }
            true
        }
    }

    private fun render() {
        if (!::mic.isInitialized) return
        val (label, color) = when (state) {
            State.IDLE -> "🎤\nHold to talk · tap to latch" to colors.idle
            State.RECORDING -> "● Listening…\nRelease to insert" to colors.rec
            State.LATCHED -> "● Listening…\nTap to finish" to colors.rec
            State.WORKING -> "Cleaning up…" to colors.busy
        }
        mic.text = label
        mic.background = rounded(color)
    }

    private fun showStatus(text: String, opensApp: Boolean = false) {
        statusOpensApp = opensApp
        if (::status.isInitialized) status.text = text
    }

    private fun idleHint() = when {
        !hasMicPermission() -> "Tap here to allow the microphone".also { statusOpensApp = true }
        settings.apiKey.isBlank() -> "Tap here to add a Gemini API key".also { statusOpensApp = true }
        else -> "Murmur".also { statusOpensApp = false }
    }

    // ---- Mic: hold to talk, or tap to latch ----

    @SuppressLint("ClickableViewAccessibility")
    private fun onMicTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> when (state) {
                State.IDLE -> if (startRecording()) {
                    pressedAt = SystemClock.uptimeMillis()
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                State.LATCHED -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    finishRecording()
                }
                else -> Unit
            }
            MotionEvent.ACTION_UP -> if (state == State.RECORDING) {
                if (SystemClock.uptimeMillis() - pressedAt < TAP_MS) {
                    state = State.LATCHED
                    render()
                } else {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    finishRecording()
                }
            }
            MotionEvent.ACTION_CANCEL -> if (state == State.RECORDING) cancelRecording()
        }
        return true
    }

    private fun startRecording(): Boolean {
        if (!hasMicPermission() || settings.apiKey.isBlank()) {
            showStatus(idleHint(), opensApp = true)
            return false
        }
        try {
            recorder.start()
        } catch (e: Exception) {
            showStatus("Microphone unavailable: ${e.message}")
            return false
        }
        failedAudio = null
        state = State.RECORDING
        recordingSince = SystemClock.uptimeMillis()
        render()
        tick()
        return true
    }

    private fun tick() {
        if (state != State.RECORDING && state != State.LATCHED) return
        if (!recorder.isRecording) { // hit the 5-minute cap
            finishRecording()
            return
        }
        val s = (SystemClock.uptimeMillis() - recordingSince) / 1000
        showStatus("%d:%02d".format(s / 60, s % 60))
        main.postDelayed(::tick, 250)
    }

    private fun cancelRecording() {
        recorder.cancel()
        state = State.IDLE
        render()
        showStatus("Cancelled")
    }

    private fun finishRecording() {
        val wav = recorder.stop()
        if (Recorder.durationMs(wav) < MIN_MS) {
            state = State.IDLE
            render()
            showStatus("Too short. Hold the mic while you speak")
            return
        }
        process(wav)
    }

    // ---- Pipeline ----

    private fun process(wav: ByteArray) {
        val id = ++job
        state = State.WORKING
        render()
        showStatus("Transcribing…")
        worker.execute {
            val result = runCatching { Dictation.run(settings, systemPrompt, wav) }
            main.post {
                if (id != job) return@post
                state = State.IDLE
                render()
                result.fold(
                    onSuccess = { out ->
                        if (out.text.isBlank()) showStatus("No speech heard")
                        else {
                            insert(out.text)
                            showStatus("%.1f s%s".format(out.ms / 1000.0, if (out.reason.isEmpty()) "" else " · rules only (${out.reason})"))
                        }
                    },
                    onFailure = { e ->
                        failedAudio = wav
                        val kind = Dictation.logFailure(settings, e)
                        showStatus(if (kind == "rate_limit") "Gemini rate limit. Tap to retry" else "Failed ($kind). Tap to retry")
                    },
                )
            }
        }
    }

    private fun insert(text: String) {
        val ic = currentInputConnection
        if (ic == null) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Murmur", text))
            showStatus("No text field. Copied to clipboard")
            return
        }
        // Add a separating space when dictating right after a word, as a person typing would.
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val first = text.first()
        val space = before.isNotEmpty() && !before.last().isWhitespace() && (first.isLetterOrDigit() || first in "\"'(")
        val out = if (space) " $text" else text
        ic.commitText(out, 1)
        lastInserted = out
    }

    // ---- Other keys ----

    private fun onStatusTap() {
        val audio = failedAudio
        when {
            audio != null && state == State.IDLE -> process(audio)
            statusOpensApp -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun undoLast() {
        val last = lastInserted ?: return showStatus("Nothing to undo")
        val ic = currentInputConnection ?: return
        if (ic.getTextBeforeCursor(last.length, 0)?.toString() == last) {
            ic.deleteSurroundingText(last.length, 0)
            lastInserted = null
            showStatus("Removed the last dictation")
        } else {
            showStatus("The cursor has moved; nothing undone")
        }
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        val options = currentInputEditorInfo?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val hasAction = (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0 &&
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        if (hasAction) ic.performEditorAction(action) else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    private fun switchKeyboard() {
        if (!switchToPreviousInputMethod()) getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    private fun warm() {
        val now = SystemClock.uptimeMillis()
        if (settings.apiKey.isBlank() || now - lastWarm < 60_000) return
        lastWarm = now
        val gemini = Gemini(settings.apiKey, settings.model, "")
        worker.execute { gemini.warm() }
    }

    private fun hasMicPermission() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAP_MS = 350L // a shorter press latches recording on
        private const val MIN_MS = 400L
    }
}
