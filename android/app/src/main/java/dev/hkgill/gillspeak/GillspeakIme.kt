package dev.hkgill.gillspeak

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
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

/**
 * A voice keyboard: hold the mic to talk and release to insert, or tap it to latch and tap again to finish.
 * Audio goes to Gemini, which returns a verbatim transcript and a cleaned version; the cleaned text is used
 * when the validator accepts it, otherwise the rules-cleaned transcript (as in gillspeak's daemon).
 */
class GillspeakIme : InputMethodService(), MicController.Ui {
    private lateinit var mic: MicController
    private val main = Handler(Looper.getMainLooper())
    private var lastInserted: String? = null
    private var inputSession = 0 // changes whenever the keyboard moves to another field

    private lateinit var statusView: TextView
    private lateinit var micKey: TextView
    private lateinit var colors: Palette

    override fun onCreate() {
        super.onCreate()
        mic = MicController(this, this)
    }

    override fun onDestroy() {
        mic.shutdown()
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!restarting) inputSession++
    }

    override fun onFinishInput() {
        inputSession++
        super.onFinishInput()
    }

    override fun field(): Any? {
        val info = currentInputEditorInfo ?: return null
        return if (info.inputType == InputType.TYPE_NULL || currentInputConnection == null) null else inputSession
    }

    override fun blocked(): String? =
        if (isPasswordInput(currentInputEditorInfo?.inputType ?: 0)) "Dictation is off in password fields" else null

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!mic.isActive && !mic.canRetry) showIdleHint()
        mic.warm()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        mic.cancel() // the keyboard went away mid-recording
        super.onFinishInputView(finishingInput)
    }

    // ---- UI ----

    override fun onCreateInputView(): View {
        colors = Palette.of(this)
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

        statusView = TextView(this).apply {
            setTextColor(colors.dim)
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 2
            setOnClickListener { onStatusTap() }
        }
        root.addView(row(dp(44),
            key("⌨") { switchKeyboard() } to dp(56),
            statusView to 0,
            key("↶") { undoLast() } to dp(56),
        ))

        micKey = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(Color.WHITE)
            setOnTouchListener(::onMicTouch)
        }
        root.addView(row(dp(150), micKey to 0))

        root.addView(row(dp(50),
            repeatingKey("⌫") { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) } to dp(80),
            key("space") { currentInputConnection?.commitText(" ", 1) } to 0,
            key("↵") { enter() } to dp(80),
        ))
        render(mic.state)
        showIdleHint()
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

    private fun key(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(colors.text)
        background = rounded(colors.key, dp(10).toFloat())
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

    override fun render(state: MicController.State) {
        if (!::micKey.isInitialized) return
        micKey.text = when (state) {
            MicController.State.IDLE -> "🎤\nHold to talk · tap to latch"
            MicController.State.RECORDING -> "● Listening…\nRelease to insert"
            MicController.State.LATCHED -> "● Listening…\nTap to finish"
            MicController.State.WORKING -> "Cleaning up…"
        }
        micKey.background = rounded(colors.forState(state), dp(10).toFloat())
    }

    override fun status(text: String, opensApp: Boolean) {
        if (::statusView.isInitialized) statusView.text = text
    }

    private fun showIdleHint() {
        val (hint, opensApp) = mic.idleHint()
        status(blocked() ?: hint, opensApp)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onMicTouch(v: View, e: MotionEvent): Boolean {
        val tick = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> mic.press()
            MotionEvent.ACTION_UP -> mic.release()
            MotionEvent.ACTION_CANCEL -> { mic.cancel(); false }
            else -> false
        }
        if (tick) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        return true
    }

    override fun insert(text: String) {
        val ic = currentInputConnection ?: return status("No text field")
        val out = withLeadingSpace(ic.getTextBeforeCursor(1, 0)?.toString().orEmpty(), text)
        ic.commitText(out, 1)
        lastInserted = out
    }

    // ---- Other keys ----

    private fun onStatusTap() {
        when {
            mic.canRetry -> mic.retry()
            mic.needsSetup() -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun undoLast() {
        val last = lastInserted ?: return status("Nothing to undo")
        val ic = currentInputConnection ?: return
        if (ic.getTextBeforeCursor(last.length, 0)?.toString() == last) {
            ic.deleteSurroundingText(last.length, 0)
            lastInserted = null
            status("Removed the last dictation")
        } else {
            status("The cursor has moved; nothing undone")
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
}

/** True for every kind of password field: text, visible, web and numeric. */
fun isPasswordInput(inputType: Int): Boolean {
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (inputType and InputType.TYPE_MASK_CLASS) {
        InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
}

/** Adds a separating space when dictating right after a word, as a person typing would. */
fun withLeadingSpace(before: String, text: String): String {
    val first = text.firstOrNull() ?: return text
    val space = before.isNotEmpty() && !before.last().isWhitespace() && (first.isLetterOrDigit() || first in "\"'(")
    return if (space) " $text" else text
}
