package dev.hkgill.gillspeak

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings.Secure
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/**
 * Floating mic: shows over (or above) whatever keyboard is open while a text field has focus, so
 * dictation never needs a keyboard switch. Same gestures as the keyboard's mic: hold to talk, or tap to latch.
 * Drag it to move it, or drop it on the target at the bottom of the screen to snooze it for a while; size, shape and
 * snooze length come from the app's settings. The accessibility service is needed to see
 * when a keyboard and a text field are on screen, and to insert text into other apps' fields.
 */
class BubbleService : AccessibilityService(), MicController.Ui {
    private lateinit var mic: MicController
    private lateinit var settings: Settings
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())

    private lateinit var button: BubbleView
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Never take focus: the text field and its keyboard must stay active underneath.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }
    private var shown = false
    private var keyboardTop = 0
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var message: String? = null // shown in the bar instead of the idle label, until it expires
    private val refresh = Runnable { refresh() }
    private var lastSummary = ""
    private var lastEvented: AccessibilityNodeInfo? = null
    private var settleChecks = 0 // looks at a keyboard still sliding in, before showing anyway
    private val clearMessage = Runnable { message = null; label() }
    // Its own Runnable: accessibility events cancel and repost [refresh], which would drop the end-of-snooze check.
    private val wake = Runnable { refresh() }

    private lateinit var target: SnoozeTargetView
    private val targetParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Only ever looked at: touches go straight through to the bubble and the app underneath.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    override fun onServiceConnected() {
        settings = Settings(this)
        mic = MicController(this, this)
        wm = getSystemService(WindowManager::class.java)
        button = BubbleView(this) { mic.level }.apply {
            setOnTouchListener(::onTouch)
            accessibilityDelegate = snoozeAction
        }
        target = SnoozeTargetView(this).apply { visibility = View.INVISIBLE }
        prefsListener = settings.onBubbleChange { if (shown) layout() }
        scheduleWake()
        // Read app names now, in the background, so the first voice command doesn't wait ~5 s for them.
        Thread({ runCatching { Actions(applicationContext).apps() } }, "gillspeak-apps").apply { priority = Thread.MIN_PRIORITY }.start()
        Log.i(Dictation.TAG, "bubble service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Remember the last text field an event came from: some apps don't report input focus to lookups.
        event?.source?.takeIf { it.isEditable }?.let { lastEvented = it }
        // Windows and focus change in bursts; settle before looking.
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 80)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (::mic.isInitialized) mic.shutdown()
        main.removeCallbacks(wake)
        hide()
        super.onDestroy()
    }

    // ---- When to show ----

    private fun refresh() {
        if (dragging) return // our own window moving fires window events; don't snap back mid-drag
        val keyboard = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            ?.let { w -> Rect().also(w::getBoundsInScreen) }?.takeIf { it.height() > 0 }
        val field = focusedField()
        // gillspeak's own keyboard already has a big mic.
        val ownKeyboard = Secure.getString(contentResolver, Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true
        val snoozed = settings.snoozed()
        val summary = "keyboard=${keyboard?.toShortString()} field=${field?.className} ownKeyboard=$ownKeyboard state=${mic.state} snoozed=$snoozed"
        if (summary != lastSummary) {
            Log.d(Dictation.TAG, "bubble: $summary")
            if (keyboard != null && field == null) Log.d(Dictation.TAG, "bubble: no field; windows: ${describeWindows()}")
            lastSummary = summary
        }
        when {
            // Snoozed: stay hidden whatever is on screen. Snoozing needs an idle bubble, so nothing is in flight.
            snoozed && mic.state != MicController.State.WORKING -> hide()
            // A keyboard still sliding in reaches past the bottom of the screen. Placing the bubble against it
            // made the bubble jump up as the keyboard settled, so wait for it (but not forever).
            keyboard != null && !shown && keyboard.bottom > screen().second && settleChecks < MAX_SETTLE_CHECKS -> {
                settleChecks++
                main.postDelayed(refresh, SETTLE_MS)
            }
            // Any open keyboard gets the bubble, except over password fields and gillspeak's own keyboard.
            keyboard != null && field?.isPassword != true && !ownKeyboard -> {
                // Keyboards change height while typing (Samsung's suggestion strip comes and goes). Anchor to the
                // highest the keyboard has been since it opened, so the bubble stays put instead of bobbing.
                keyboardTop = if (shown) minOf(keyboardTop, keyboard.top) else keyboard.top
                settleChecks = 0
                show()
            }
            mic.state == MicController.State.WORKING -> Unit // stay visible until the text lands
            else -> {
                // The keyboard closed mid-recording: discard it. Undelivered text and failed audio are kept, so
                // after switching apps a tap can still insert or retry them in the new field.
                mic.cancel(null)
                settleChecks = 0
                hide()
            }
        }
        scheduleWake()
    }

    /** Runs [refresh] when a snooze ends or the night snooze starts, as no accessibility event may come then. */
    private fun scheduleWake() {
        main.removeCallbacks(wake)
        val now = System.currentTimeMillis()
        settings.nextSnoozeChange(now)?.let { main.postDelayed(wake, it - now + 50) }
    }

    /**
     * The text field with input focus. The service-level lookup only searches the "active" window, which some
     * apps (Claude, for one) don't report as such, so fall back to searching every app window.
     */
    override fun field(): Any? = focusedField()

    override fun blocked(): String? = if (focusedField()?.isPassword == true) "Dictation is off in password fields" else null

    private fun focusedField(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }?.let { return it }
        windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
            .firstOrNull { it.isEditable }?.let { return it }
        return lastEvented?.takeIf { it.refresh() && it.isEditable && it.isFocused }
    }

    /** Debug aid: what the service can see in each window when a keyboard is up but no field was found. */
    private fun describeWindows(): String = windows.joinToString("; ") { w ->
        val root = w.root
        var editable = 0
        var nodes = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || nodes > 2000) return
            nodes++
            if (n.isEditable) editable++
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        "type=${w.type} pkg=${root?.packageName} root=${root != null} nodes=$nodes editable=$editable"
    }

    private fun show() {
        if (!shown || keyboardTop != laidOutFor) layout()
        if (!shown) {
            // The target goes in first so the bubble, added after it, is drawn on top while dragged over it.
            placeTarget()
            wm.addView(target, targetParams)
            wm.addView(button, params)
            shown = true
            mic.warm(models = false) // the local model waits for a press: see MicController.warm
        }
    }

    private fun hide() {
        if (!shown) return
        wm.removeView(button)
        target.armed = false
        target.visibility = View.INVISIBLE
        wm.removeView(target)
        shown = false
    }

    // ---- Look and position ----

    private val isBar get() = settings.bubbleShape == "bar"

    private var laidOutFor = Int.MIN_VALUE
    private var widthAnimator: android.animation.ValueAnimator? = null

    /**
     * The window is the shape plus [BubbleView.inset] on every side, room for the shadow. While recording or
     * transcribing, the square stretches into a pill toward the middle of the screen, keeping its outer edge put.
     */
    private fun layout() {
        laidOutFor = keyboardTop
        val size = dp(settings.bubbleSize)
        val inset = button.inset
        val (screenW, screenH) = screen()
        val square = size + 2 * inset
        val pill = !isBar && mic.isActive
        button.bar = isBar
        button.pill = pill
        params.height = square
        params.y = (keyboardTop + dp(settings.bubbleDy) - inset).coerceIn(0, maxOf(0, screenH - params.height))
        widthAnimator?.cancel()
        if (isBar) {
            params.width = screenW
            params.x = 0
        } else {
            val centre = settings.bubbleX * screenW
            val targetW = if (pill) (square + size * 2.0f).toInt() else square
            val onRight = centre > screenW / 2
            fun place(w: Int) {
                params.width = w
                // Keep the edge nearest the screen side fixed, so the pill grows inward.
                val squareLeft = (centre - square / 2f).toInt().coerceIn(0, maxOf(0, screenW - square))
                params.x = (if (onRight) squareLeft + square - w else squareLeft).coerceIn(0, maxOf(0, screenW - w))
            }
            if (shown && params.width != targetW) {
                widthAnimator = android.animation.ValueAnimator.ofInt(params.width, targetW).apply {
                    duration = 180
                    addUpdateListener {
                        place(it.animatedValue as Int)
                        if (shown) wm.updateViewLayout(button, params)
                    }
                    start()
                }
            } else {
                place(targetW)
            }
        }
        render(mic.state)
        if (shown) wm.updateViewLayout(button, params)
    }

    override fun render(state: MicController.State) {
        if (!::button.isInitialized) return
        button.state = state
        if (!isBar && button.pill != mic.isActive) layout() // stretch into the pill, or back to the square
        label()
    }

    private var timer = ""

    /** The circle shows only its icon; the bar also says what's going on. */
    private fun label() {
        button.retry = mic.canRetry
        button.label = if (!isBar) when (mic.state) {
            MicController.State.RECORDING, MicController.State.LATCHED -> timer.ifEmpty { "0:00" }
            else -> ""
        } else when {
            mic.state == MicController.State.RECORDING -> "$timer · release to type"
            mic.state == MicController.State.LATCHED -> "$timer · tap to stop"
            mic.state == MicController.State.WORKING -> "Typing…"
            mic.canRetry -> message ?: "Didn't catch that · tap to retry"
            else -> message ?: "Hold to talk · tap to latch"
        }
    }

    override fun status(text: String, opensApp: Boolean) {
        if (mic.isActive && text.firstOrNull()?.isDigit() == true) { // the recording timer
            timer = text
            label()
            return
        }
        timer = ""
        if (mic.state == MicController.State.WORKING) return
        if (isBar) {
            message = text
            main.removeCallbacks(clearMessage)
            if (!mic.canRetry) main.postDelayed(clearMessage, 4000)
            label()
        } else if (text != Settings.APP_NAME) {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Gestures: hold to talk, tap to latch, drag to move ----
    //
    // The mic doesn't start the moment a finger lands: that would make every drag start (and then tear down)
    // a recording, which stutters. Held still for HOLD_MS it becomes hold-to-talk; moved first, it's a drag;
    // lifted first, it's a tap (which latches recording on, or finishes a latched one).

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var snoozable = false // this drag can end on the snooze target
    private var holding = false // the mic started under this finger
    private val startHold = Runnable {
        if (mic.press()) {
            holding = true
            button.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                button.pressed(true)
                downX = e.rawX
                downY = e.rawY
                startX = params.x
                startY = params.y
                dragging = false
                holding = false
                if (mic.state == MicController.State.IDLE && !mic.canRetry && !mic.needsSetup()) main.postDelayed(startHold, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !holding && abs(e.rawX - downX) + abs(e.rawY - downY) > dp(8)) {
                    dragging = true
                    main.removeCallbacks(startHold)
                    button.pressed(false)
                    // Like Wispr Flow, only an idle bubble snoozes: never mid-dictation or with text waiting.
                    snoozable = mic.state == MicController.State.IDLE && !mic.canRetry
                    if (snoozable) showTarget()
                }
                if (dragging) {
                    val (screenW, screenH) = screen()
                    if (!isBar) params.x = (startX + (e.rawX - downX).toInt()).coerceIn(0, maxOf(0, screenW - params.width))
                    params.y = (startY + (e.rawY - downY).toInt()).coerceIn(0, maxOf(0, screenH - params.height))
                    wm.updateViewLayout(button, params)
                    if (snoozable) armTarget()
                }
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(startHold)
                button.pressed(false)
                when {
                    dragging -> {
                        dragging = false
                        // Dropped on the target: snooze, and keep the saved spot so it comes back where it was.
                        if (target.armed) snooze() else savePosition()
                        hideTarget()
                        main.post(refresh)
                    }
                    holding -> if (mic.release()) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    mic.canRetry -> mic.retry()
                    !mic.isActive && mic.needsSetup() ->
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    mic.state == MicController.State.LATCHED -> {
                        mic.press() // finishes the latched recording
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    mic.state == MicController.State.IDLE -> if (mic.press()) { // a tap: start and latch
                        mic.release()
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
                holding = false
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(startHold)
                button.pressed(false)
                dragging = false
                hideTarget()
                if (holding) mic.cancel(null)
                holding = false
            }
        }
        return true
    }

    // ---- Snooze ----

    /** Centres the target near the bottom of the screen, clear of the navigation bar. */
    private fun placeTarget() {
        val (screenW, screenH) = screen()
        targetParams.width = target.windowWidth
        targetParams.height = target.windowHeight
        targetParams.x = (screenW - target.windowWidth) / 2
        targetParams.y = screenH - target.windowHeight - dp(56)
    }

    private fun showTarget() {
        placeTarget()
        target.label = "Snooze ${Snooze.label(settings.snoozeMinutes)}"
        target.armed = false
        target.alpha = 0f
        target.visibility = View.VISIBLE
        target.animate().alpha(1f).setDuration(150).start()
        wm.updateViewLayout(target, targetParams)
    }

    private fun hideTarget() {
        snoozable = false
        if (!::target.isInitialized || target.visibility != View.VISIBLE) return
        target.armed = false
        target.animate().alpha(0f).setDuration(120).withEndAction { target.visibility = View.INVISIBLE }.start()
    }

    /** Arms the target while the bubble is over it, with one buzz on the way in. */
    private fun armTarget() {
        val over = Snooze.over(
            params.x + params.width / 2f, params.y + params.height / 2f,
            targetParams.x + target.centreX, targetParams.y + target.centreY,
            target.radius + dp(settings.bubbleSize) / 2f,
        )
        if (over && !target.armed) button.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        target.armed = over
    }

    private fun snooze() {
        val until = settings.snooze()
        hide()
        scheduleWake()
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until))
        Toast.makeText(this, "Bubble snoozed until $time. End it early in the ${Settings.APP_NAME} app", Toast.LENGTH_LONG).show()
        Log.i(Dictation.TAG, "bubble snoozed for ${settings.snoozeMinutes} min")
    }

    /** Screen-reader users can't drag, so snoozing is also an action on the bubble. */
    private val snoozeAction = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            if (mic.state == MicController.State.IDLE && !mic.canRetry) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_snooze, "Snooze for ${Snooze.label(settings.snoozeMinutes)}"))
            }
        }

        override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
            if (action != R.id.action_snooze) return super.performAccessibilityAction(host, action, args)
            if (mic.state != MicController.State.IDLE || mic.canRetry) return false
            snooze()
            return true
        }
    }

    private fun savePosition() {
        val x = if (isBar) settings.bubbleX else (params.x + params.width / 2f) / screen().first
        settings.setBubblePosition(x, ((params.y + button.inset - keyboardTop) / resources.displayMetrics.density).toInt())
    }

    /**
     * The whole physical screen, in pixels. displayMetrics leaves out the navigation bar, but keyboards reach
     * the bottom edge, so clamping to it made a bubble dropped low on the keyboard jump back up.
     */
    private fun screen(): Pair<Int, Int> = wm.currentWindowMetrics.bounds.let { it.width() to it.height() }


    // ---- Inserting text into another app's field ----

    override fun insert(text: String) {
        // Only ever the field in focus now (the controller has checked it's the one dictated into), never a
        // remembered node, which might since have become a password field.
        val node = focusedField()?.takeIf { !it.isPassword }
        val set = node != null && setText(node, text)
        var pasted = false
        if (!set) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(Settings.APP_NAME, text))
            pasted = node?.performAction(AccessibilityNodeInfo.ACTION_PASTE) == true
            if (!pasted) Toast.makeText(this, "Copied. Paste it where you want it", Toast.LENGTH_LONG).show()
        }
        Log.i(Dictation.TAG, "insert: field=${node?.className} pkg=${node?.packageName} setText=$set paste=$pasted")
        main.post(refresh)
    }

    /** Splices the text in at the cursor (replacing any selection) and puts the cursor after it. */
    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val current = realText(
            node.text?.toString(), node.hintText?.toString(), node.isShowingHintText,
            node.textSelectionStart, node.textSelectionEnd, node.packageName?.toString(),
        )
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start !in 0..current.length || end !in 0..current.length) {
            start = current.length
            end = current.length
        }
        if (start > end) start = end.also { end = start }
        val insert = withLeadingSpace(current.substring(0, start), text)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current.substring(0, start) + insert + current.substring(end))
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        val cursor = start + insert.length
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
        return true
    }

    companion object {
        private const val HOLD_MS = 150L
        private const val SETTLE_MS = 60L
        private const val MAX_SETTLE_CHECKS = 10
    }
}
