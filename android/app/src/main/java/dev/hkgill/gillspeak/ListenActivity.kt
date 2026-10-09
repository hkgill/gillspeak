package dev.hkgill.gillspeak

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * What the side button's long-press opens once gillspeak is the phone's digital assistant (it handles ACTION_ASSIST).
 * A see-through screen over whatever was open: the screen dims, a teal wave runs out of the side button round the
 * edge, and the bubble drops in and listens. Listening ends after a short silence or a tap on the pill (apps never
 * see the side button being let go). The transcript is matched against [Commands], and [Actions] carries it out:
 * apps, timers and the torch straight away, texts and calls only after a tap.
 *
 * Being an activity in front is what lets it use the microphone; the same engine as dictation transcribes it.
 */
class ListenActivity : Activity(), MicController.Ui {
    private lateinit var settings: Settings
    private lateinit var mic: MicController
    private lateinit var actions: Actions
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var scrim: View
    private lateinit var edge: EdgeWaveView
    private lateinit var column: LinearLayout
    private lateinit var transcript: TextView
    private lateinit var card: LinearLayout
    private lateinit var pill: BubbleView

    private var started = false
    private var closing = false
    private var handled = false // the transcript arrived; later status lines from the controller are not ours
    private var askingContacts = false
    private var pending: Command? = null // waiting for contacts access
    private var heard = ""

    // Silence detection.
    private var listenSince = 0L
    private var lastLoud = 0L
    private var spoke = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        actions = Actions(this)
        mic = MicController(this, this)

        window.setDecorFitsSystemWindows(false)
        window.isNavigationBarContrastEnforced = false
        window.insetsController?.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && windowManager.isCrossWindowBlurEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = dp(18) }
        }

        scrim = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40_08090B, 0xB8_08090B.toInt()))
            alpha = 0f
            setOnClickListener { close() } // tapping outside the card dismisses
        }
        edge = EdgeWaveView(this) { if (mic.state == MicController.State.RECORDING || mic.state == MicController.State.LATCHED) mic.level else 0f }
        transcript = TextView(this).apply {
            textSize = 23f
            setTextColor(Brand.PAPER)
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setLineSpacing(0f, 1.15f)
            alpha = 0f
        }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Brand.NIGHT_SURFACE)
                setStroke(dp(1), 0x14_FFFFFF)
            }
            elevation = dp(8).toFloat()
            setPadding(dp(18), dp(16), dp(18), dp(16))
            visibility = View.GONE
            isClickable = true // taps on the card don't reach the scrim
        }
        pill = BubbleView(this) { mic.level }.apply {
            pill = true
            label = "Listening"
            alpha = 0f
            setOnClickListener { onPillTap() }
        }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(transcript, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16) })
            addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) })
            addView(pill, LinearLayout.LayoutParams(dp(260), dp(56) + 2 * pill.inset))
        }
        val root = FrameLayout(this).apply {
            addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(edge, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(column, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                column.setPadding(dp(20) + bars.left, 0, dp(20) + bars.right, bars.bottom + dp(20))
                insets
            }
        }
        setContentView(root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { close() }
        }
    }

    @Deprecated("Android 13 and later use onBackInvokedDispatcher")
    override fun onBackPressed() = close()

    override fun onResume() {
        super.onResume()
        if (!started) {
            started = true
            begin()
        }
    }

    /** A second long-press while it's open: listen again. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!closing && mic.state == MicController.State.IDLE) relisten()
    }

    override fun onStop() {
        super.onStop()
        // Left for another app (or the screen went off): never keep listening in the background.
        if (!askingContacts && !isFinishing && !isChangingConfigurations) {
            mic.cancel(null)
            finishQuietly()
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        mic.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---- Opening ----

    private fun begin() {
        if (mic.needsSetup()) {
            val (hint, _) = mic.idleHint()
            android.widget.Toast.makeText(this, hint.removePrefix("Tap here to ").replaceFirstChar { it.uppercase() }, android.widget.Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finishQuietly()
            return
        }
        scrim.animate().alpha(1f).setDuration(320).start()
        edge.open()
        flyIn()
        mic.warm()
        startListening()
    }

    /** The bubble drops out of the side button to the bottom of the screen. */
    private fun flyIn() {
        pill.post {
            val loc = IntArray(2).also(pill::getLocationOnScreen)
            val screenW = resources.displayMetrics.widthPixels
            val screenH = (pill.rootView.height).takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            pill.translationX = (screenW - (loc[0] + pill.width / 2f))
            pill.translationY = edge.originY * screenH - (loc[1] + pill.height / 2f)
            pill.scaleX = 0.3f
            pill.scaleY = 0.3f
            pill.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setStartDelay(120).setDuration(420).setInterpolator(DecelerateInterpolator(1.8f)).start()
        }
    }

    private fun startListening() {
        handled = false
        heard = ""
        if (!mic.latch()) return // the controller has said why, through status()
        listenSince = SystemClock.uptimeMillis()
        lastLoud = listenSince
        spoke = false
        main.post(watch)
    }

    private fun relisten() {
        card.visibility = View.GONE
        transcript.animate().alpha(0f).setDuration(150).start()
        main.removeCallbacks(closeLater)
        startListening()
    }

    /** Ends the recording after a short silence once speech has started, or gives up if nothing is said. */
    private val watch = object : Runnable {
        override fun run() {
            if (mic.state != MicController.State.LATCHED && mic.state != MicController.State.RECORDING) return
            val now = SystemClock.uptimeMillis()
            if (mic.level > SPEECH_LEVEL) {
                spoke = true
                lastLoud = now
            }
            when {
                spoke && now - lastLoud > SILENCE_MS -> mic.press()
                !spoke && now - listenSince > NOTHING_SAID_MS -> {
                    mic.cancel(null)
                    message("No speech heard")
                    closeIn(1600)
                }
                now - listenSince > MAX_LISTEN_MS -> mic.press()
                else -> main.postDelayed(this, 50)
            }
        }
    }

    private fun onPillTap() {
        when {
            mic.state == MicController.State.LATCHED || mic.state == MicController.State.RECORDING -> mic.press()
            mic.canRetry -> {
                main.removeCallbacks(closeLater)
                mic.retry()
            }
            mic.state == MicController.State.IDLE && !closing -> relisten()
        }
    }

    // ---- MicController.Ui ----

    override fun render(state: MicController.State) {
        pill.state = state
        pill.retry = mic.canRetry
        edge.working = state == MicController.State.WORKING
        pill.label = when (state) {
            MicController.State.RECORDING, MicController.State.LATCHED -> "Listening"
            MicController.State.WORKING -> "Thinking"
            MicController.State.IDLE -> pill.label
        }
    }

    override fun status(text: String, opensApp: Boolean) {
        if (handled || closing) return
        if (mic.isActive) return // the recording timer, and "Transcribing…"
        // Something went wrong before there was a transcript: no speech, too short, blocked mic, failed request.
        pill.retry = mic.canRetry
        message(text)
        if (!mic.canRetry) closeIn(2000) else closeIn(8000)
    }

    /** Every listen belongs to this screen, so the field never "changes" under it. */
    override fun field(): Any = this

    override fun blocked(): String? = null

    override fun insert(text: String) {
        handled = true
        heard = text
        showTranscript(text)
        pill.label = "Thinking"
        edge.working = true
        val command = Commands.parse(text)
        Log.i(Dictation.TAG, "command: ${command ?: "none"}")
        plan(command)
    }

    // ---- Planning and acting ----

    private fun plan(command: Command?) {
        worker.execute {
            val outcome = runCatching {
                if (command == null) Actions.Outcome.Ready(actions.searchInstead(heard)) else actions.plan(command)
            }.getOrElse {
                Log.e(Dictation.TAG, "command: planning failed", it)
                Actions.Outcome.Problem("Something went wrong", it.javaClass.simpleName)
            }
            main.post { if (!closing) show(command, outcome) }
        }
    }

    private fun show(command: Command?, outcome: Actions.Outcome) {
        edge.working = false
        when (outcome) {
            is Actions.Outcome.Problem -> {
                pill.label = outcome.title
                showCard(outcome.title, outcome.detail, null, big = false, buttons = if (outcome.askContacts) {
                    listOf("Not now" to { close() }, "Allow contacts" to { askContacts(command) })
                } else {
                    listOf("Close" to { close() })
                })
                if (!outcome.askContacts) closeIn(5000)
            }
            is Actions.Outcome.Ready -> {
                val plan = outcome.plan
                val big = command is Command.Timer || command is Command.Alarm
                if (plan.confirm == null) {
                    showCard(plan.title, plan.detail, plan.body, big, buttons = emptyList())
                    pill.label = plan.title
                    main.postDelayed({ act(plan) }, ACT_DELAY_MS)
                } else {
                    pill.label = if (command == null) "Not a command" else "Waiting for you"
                    showCard(plan.title, plan.detail, plan.body, big = false, buttons = listOf("Cancel" to { close() }, plan.confirm to { act(plan) }))
                }
            }
        }
    }

    private fun act(plan: Actions.Plan) {
        if (closing) return
        val ok = plan.run()
        settings.log("Command: $heard\n  did: ${plan.title}${if (ok) "" else " (nothing on this phone could do it)"}")
        when {
            !ok -> {
                pill.label = "Couldn't do that"
                showCard("Nothing on this phone can do that", plan.title, null, big = false, buttons = listOf("Close" to { close() }))
                closeIn(4000)
            }
            // The app's own opening animation takes over; this screen just goes.
            plan.opensApp -> finishQuietly()
            else -> {
                pill.state = MicController.State.IDLE
                pill.label = "Done"
                closeIn(1300)
            }
        }
    }

    private fun askContacts(command: Command?) {
        askingContacts = true
        pending = command
        requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CONTACTS) return
        askingContacts = false
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) plan(pending) else close()
    }

    // ---- Views ----

    private fun showTranscript(text: String) {
        transcript.text = text
        transcript.alpha = 0f
        transcript.translationY = dp(8).toFloat()
        transcript.setTextColor(Brand.PAPER)
        transcript.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    private fun message(text: String) {
        transcript.text = text
        transcript.setTextColor(DIM)
        transcript.animate().alpha(1f).setDuration(180).start()
        pill.label = if (mic.canRetry) "Tap to retry" else ""
    }

    private fun showCard(title: String, detail: String, body: String?, big: Boolean, buttons: List<Pair<String, () -> Unit>>) {
        card.removeAllViews()
        card.addView(text(title, 17f, Brand.PAPER, bold = true))
        card.addView(text(detail, 13f, DIM).apply { setPadding(0, dp(2), 0, 0) })
        if (body != null) {
            card.addView(if (big) text(body, 44f, Brand.PAPER).apply {
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                fontFeatureSettings = "tnum"
                setPadding(0, dp(8), 0, 0)
            } else text(body, 16f, Brand.INK).apply {
                background = GradientDrawable().apply {
                    cornerRadii = floatArrayOf(18f, 18f, 18f, 18f, 6f, 6f, 18f, 18f).map { it * resources.displayMetrics.density }.toFloatArray()
                    setColor(Brand.TEAL)
                }
                setPadding(dp(14), dp(9), dp(14), dp(9))
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    gravity = Gravity.END
                    topMargin = dp(12)
                }
            })
        }
        if (buttons.isNotEmpty()) {
            card.addView(LinearLayout(this).apply {
                setPadding(0, dp(14), 0, 0)
                buttons.forEachIndexed { i, (label, onClick) ->
                    val primary = i == buttons.lastIndex
                    addView(text(label, 14f, if (primary) Brand.INK else Brand.PAPER, bold = true).apply {
                        gravity = Gravity.CENTER
                        background = rounded(if (primary) Brand.TEAL else 0x1A_FFFFFF, dp(22).toFloat())
                        setOnClickListener { onClick() }
                    }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(10) })
                }
            })
        }
        card.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(View(this@ListenActivity).apply { background = rounded(Brand.TEAL, dp(3).toFloat()) }, LinearLayout.LayoutParams(dp(6), dp(6)))
            val where = if (settings.engine == Settings.ENGINE_LOCAL) "Understood on this phone" else "Transcribed by ${engineName()}, understood on this phone"
            addView(text(where, 11.5f, DIM).apply { setPadding(dp(7), 0, 0, 0) })
        })
        if (card.visibility != View.VISIBLE) {
            card.visibility = View.VISIBLE
            card.alpha = 0f
            card.translationY = dp(16).toFloat()
            card.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
    }

    private fun engineName() = if (settings.engine == Settings.ENGINE_GROQ) "Groq" else "Gemini"

    // ---- Closing ----

    private val closeLater = Runnable { close() }

    private fun closeIn(ms: Long) {
        main.removeCallbacks(closeLater)
        main.postDelayed(closeLater, ms)
    }

    /** Everything folds back into the side button. */
    private fun close() {
        if (closing) return
        closing = true
        main.removeCallbacks(watch)
        main.removeCallbacks(closeLater)
        mic.cancel(null)
        column.animate().alpha(0f).setDuration(200).start()
        scrim.animate().alpha(0f).setStartDelay(100).setDuration(300).start()
        val loc = IntArray(2).also(pill::getLocationOnScreen)
        pill.animate()
            .translationX(resources.displayMetrics.widthPixels - (loc[0] + pill.width / 2f))
            .translationY(edge.originY * pill.rootView.height - (loc[1] + pill.height / 2f))
            .scaleX(0.3f).scaleY(0.3f).setDuration(300).start()
        edge.close { finishQuietly() }
    }

    private fun finishQuietly() {
        closing = true
        if (isFinishing) return
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        else @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    companion object {
        private const val REQ_CONTACTS = 7
        private const val DIM = 0xFFA9ADB6.toInt()
        /** Recorder.level is the last 100 ms peak over 12,000: speech sits well above this, a quiet room well below. */
        private const val SPEECH_LEVEL = 0.06f
        private const val SILENCE_MS = 900L
        private const val NOTHING_SAID_MS = 7000L
        private const val MAX_LISTEN_MS = 30_000L
        /** Long enough to read the card before the app opens or the timer starts. */
        private const val ACT_DELAY_MS = 650L
    }
}
