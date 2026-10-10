package dev.hkgill.gillspeak

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
import android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS
import android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS
import android.provider.Settings.Secure
import android.text.InputType
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Settings, as designed in Claude Design ("Settings" canvas): a home screen that shows what still needs setting up,
 * then one row per section with its current value; each row opens its own page. On a wide screen (a Fold's inner
 * screen) the list stays on the left and the picked page opens on the right.
 */
class MainActivity : Activity() {
    private class Step(
        val name: String, val short: String, val done: Boolean, val required: Boolean,
        val why: String, val action: String, val doneValue: String, val run: () -> Unit,
    )

    private lateinit var settings: Settings
    private lateinit var t: Look
    private lateinit var f: Fonts
    private var night = false
    private var twoPane = false
    private var page = HOME
    private var showDone = false
    private var draft = "" // the dictionary word being typed, kept across redraws

    private lateinit var listScroll: ScrollView
    private var detailScroll: ScrollView? = null
    private var shownList = ""
    private var shownDetail = ""

    // The bubble page's live preview, resized while the slider moves without redrawing the page.
    private var preview: BubbleView? = null
    private var previewBox: FrameLayout? = null
    private var sizeValue: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val poll = Runnable { render(fromPoll = true) }
    private val snoozeOver = Runnable { render() }
    private var backRegistered = false
    private val back = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { go(HOME) } else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        f = Fonts(this)
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        t = if (night) Look.DARK else Look.LIGHT
        twoPane = resources.configuration.screenWidthDp >= TWO_PANE_DP
        page = savedInstanceState?.getString("page") ?: if (twoPane) ENGINE else HOME
        if (twoPane && page == HOME) page = ENGINE
        showDone = savedInstanceState?.getBoolean("showDone") ?: false

        actionBar?.hide()
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        listScroll = scroller()
        val root: ViewGroup = if (twoPane) {
            detailScroll = scroller()
            LinearLayout(this).apply {
                addView(listScroll, LinearLayout.LayoutParams(dp(400), MATCH_PARENT))
                addView(detailScroll, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { marginStart = dp(12); marginEnd = dp(12) })
            }
        } else {
            FrameLayout(this).apply { addView(listScroll) }
        }
        root.setBackgroundColor(t.bg)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        window.insetsController?.setSystemBarsAppearance(if (night) 0 else lightBars, lightBars)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("page", page)
        outState.putBoolean("showDone", showDone)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        handler.removeCallbacks(snoozeOver)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    @Deprecated("Android 12 and older; newer versions use the OnBackInvokedCallback")
    override fun onBackPressed() {
        if (!twoPane && page != HOME) go(HOME) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun go(to: String) {
        page = to
        render()
    }

    // ---- Drawing the panes ----

    /**
     * Redraws what's on screen, keeping each pane's scroll position unless it now shows a different page. A poll
     * (download progress) skips a page with a text field, so typing isn't interrupted.
     */
    private fun render(fromPoll: Boolean = false) {
        handler.removeCallbacks(poll)
        handler.removeCallbacks(snoozeOver)
        val asr = LocalAsr.refresh(this)
        val gemma = LocalLlm.refresh(this)
        if (twoPane) {
            put(listScroll, HOME, page(HOME, asr, gemma), "list")
            if (!fromPoll || page == ENGINE) put(detailScroll!!, page, page(page, asr, gemma), "detail")
        } else if (!fromPoll || page == HOME || page == ENGINE) {
            put(listScroll, page, page(page, asr, gemma), "list")
        }
        updateBack()
        val busy = { s: LocalAsr.Status -> s is LocalAsr.Status.Downloading || s == LocalAsr.Status.Installing }
        if (busy(asr) || busy(gemma)) handler.postDelayed(poll, 1000)
        settings.snoozedUntil()?.let { handler.postDelayed(snoozeOver, it - System.currentTimeMillis() + 50) }
    }

    private fun put(scroll: ScrollView, shows: String, content: View, which: String) {
        val same = (if (which == "list") shownList else shownDetail) == shows
        if (which == "list") shownList = shows else shownDetail = shows
        val y = if (same) scroll.scrollY else 0
        scroll.removeAllViews()
        scroll.addView(content)
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun page(which: String, asr: LocalAsr.Status, gemma: LocalAsr.Status): View = when (which) {
        ENGINE -> detail("Speech and understanding", enginePage(asr, gemma))
        BUBBLE -> detail("Bubble", bubblePage())
        SNOOZE -> detail("Snooze", snoozePage())
        WORDS -> detail("Words and keys", wordsPage())
        RECENT -> detail("Recent dictations", recentPage())
        else -> homePage(asr, gemma)
    }

    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val want = !twoPane && page != HOME
        if (want == backRegistered) return
        backRegistered = want
        if (want) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back!!)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back!!)
    }

    // ---- Home ----

    private fun homePage(asr: LocalAsr.Status, gemma: LocalAsr.Status): View {
        val col = column(dp(16), dp(8), dp(16), dp(32), gap = 16)
        val local = settings.engine == Settings.ENGINE_LOCAL
        val chip = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(t.accSoft, dp(999).toFloat())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            addView(View(this@MainActivity).apply { background = rounded(t.accT, dp(4).toFloat()) }, LinearLayout.LayoutParams(dp(8), dp(8)))
            addView(text(if (local) "On this phone" else "Sends audio", 14f, t.accT, f.bold).apply { setPadding(dp(8), 0, 0, 0) })
        }
        val chipTarget = FrameLayout(this).apply {
            minimumHeight = dp(48)
            addView(chip, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            contentDescription = if (local) "Speech engine: on this phone. Change" else "Speech engine: ${engineName()}, sends audio. Change"
            setOnClickListener { go(ENGINE) }
        }
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
            addView(LogoView(this@MainActivity), LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(text(Settings.APP_NAME, 26f, t.ink, f.bold).apply {
                letterSpacing = -0.02f
                setPadding(dp(12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(chipTarget)
        })
        col.addView(text(
            "Hold the bubble to talk. Hold the side button to ask. " +
                if (local) "Nothing leaves your phone." else "${engineName()} gets your audio.",
            16f, t.ink2,
        ).apply { setPadding(dp(4), 0, dp(4), 0); setLineSpacing(0f, 1.15f) })

        col.addView(setupCard())

        val engineExtra = progressOf(gemma) ?: progressOf(asr).takeIf { local }
        col.addView(card(dp(6), dp(6)).apply {
            addView(navRow("Speech and understanding", engineSummary(asr, gemma), ENGINE, engineExtra?.let { bar(it, 4) }))
            addView(navRow("Bubble", bubbleSummary(), BUBBLE))
            addView(navRow("Snooze", snoozeSummary(), SNOOZE, summaryColor = if (settings.snoozed()) t.attT else t.ink2))
            addView(navRow("Words and keys", wordsSummary(), WORDS))
        })

        col.addView(recentCard())

        col.addView(text(
            "The bubble uses Android's accessibility service only to see when a keyboard and a text field are on screen, " +
                "and to type your dictation into that field. It skips password fields and reads nothing else. Dictations " +
                "are kept only on this phone.",
            13f, t.ink2,
        ).apply { setPadding(dp(4), 0, dp(4), 0); setLineSpacing(0f, 1.1f) })
        return col
    }

    private fun steps(): List<Step> {
        val mic = granted(Manifest.permission.RECORD_AUDIO)
        val services = Secure.getString(contentResolver, Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val bubble = ComponentName(this, BubbleService::class.java)
        val bubbleOn = services.split(':').any { it == bubble.flattenToString() || it == bubble.flattenToShortString() }
        val ime = getSystemService(InputMethodManager::class.java)
        val keyboardOn = ime.enabledInputMethodList.any { it.packageName == packageName }
        // The side button's long-press opens the digital assistant. That role can't be requested from an app, so
        // this opens the screen where it's chosen.
        val assistant = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
        return listOf(
            Step("Microphone", "microphone", mic, true, "So ${Settings.APP_NAME} can hear you.", "Allow", "Allowed") {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            },
            Step("Floating bubble", "bubble", bubbleOn, true, "Turn on ${getString(R.string.bubble_label)} to get a mic over any keyboard.", "Turn on", "On") {
                startActivity(Intent(ACTION_ACCESSIBILITY_SETTINGS))
            },
            Step("Side button", "side button", assistant, true, "Make ${Settings.APP_NAME} your digital assistant to give commands.", "Set up", "On") {
                startActivity(Intent(ACTION_VOICE_INPUT_SETTINGS))
            },
            Step("Keyboard", "keyboard", keyboardOn, false, "A full-screen mic keyboard.", "Turn on", "On") {
                if (keyboardOn) ime.showInputMethodPicker() else startActivity(Intent(ACTION_INPUT_METHOD_SETTINGS))
            },
            Step("Contacts", "contacts", granted(Manifest.permission.READ_CONTACTS), false, "So you can say “message Sam”.", "Allow", "Allowed") {
                requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 2)
            },
        )
    }

    private fun setupCard(): View {
        val steps = steps()
        val done = steps.filter { it.done }
        val todo = steps.filter { !it.done }.sortedBy { !it.required }
        val card = card(dp(18), dp(6))
        card.addView(LinearLayout(this).apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(text("Setup", 18f, t.ink, f.bold), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(text("${done.size} of ${steps.size} done", 14f, t.ink2))
        })
        card.addView(LinearLayout(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(dp(20), dp(12), dp(20), dp(6))
            val colors = done.map { t.done } + todo.map { if (it.required) t.attT else t.field }
            colors.forEachIndexed { i, color ->
                addView(View(this@MainActivity).apply { background = rounded(color, dp(3).toFloat()) },
                    LinearLayout.LayoutParams(0, dp(6), 1f).apply { if (i > 0) marginStart = dp(4) })
            }
        })
        for (step in todo) {
            val badge = if (step.required) {
                text("!", 17f, t.attT, f.heavy).apply { gravity = Gravity.CENTER; background = rounded(t.attSoft, dp(16).toFloat()) }
            } else {
                FrameLayout(this).apply {
                    background = rounded(t.field, dp(16).toFloat())
                    addView(View(this@MainActivity).apply { background = rounded(t.ink2, dp(2).toFloat()) },
                        FrameLayout.LayoutParams(dp(12), dp(3), Gravity.CENTER))
                }
            }
            badge.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val title = text(step.name, 16f, t.ink, f.semibold)
            if (!step.required) title.append(styled(" · optional", t.ink2, f.regular))
            card.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(20), dp(10), dp(12), dp(10))
                addView(badge, LinearLayout.LayoutParams(dp(32), dp(32)))
                addView(stack(title, text(step.why, 14f, t.ink2).apply { setLineSpacing(0f, 1.1f) }).apply { setPadding(dp(14), 0, dp(8), 0) },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(pill(step.action, filled = step.required, description = "${step.action}: ${step.name}") { step.run() })
            })
        }
        if (done.isNotEmpty()) {
            val names = done.map { it.short }
            val joined = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
            val label = if (todo.isEmpty()) "Everything is set up"
            else joined.replaceFirstChar { it.uppercase() } + if (names.size == 1) " is ready" else " are ready"
            val check = FrameLayout(this).apply {
                background = rounded(t.done, dp(16).toFloat())
                addView(IconView(this@MainActivity, IconView.Kind.CHECK, t.onDone, 3.2f), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            }
            val chevron = IconView(this, IconView.Kind.DOWN, t.ink2).apply { rotation = if (showDone) 180f else 0f }
            card.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(20), dp(10), dp(20), dp(10))
                addView(check, LinearLayout.LayoutParams(dp(32), dp(32)))
                addView(text(label, 15f, t.ink2).apply { setPadding(dp(14), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(chevron, LinearLayout.LayoutParams(dp(20), dp(20)))
                contentDescription = "$label. " + if (showDone) "Hide details" else "Show details"
                ripple(this, 0)
                setOnClickListener { showDone = !showDone; render() }
            })
            if (showDone) {
                val list = column(dp(66), 0, dp(20), dp(10))
                for (step in done) list.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(48)
                    addView(text(step.name, 15f, t.ink), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(text(step.doneValue, 15f, t.ink2))
                    ripple(this, 12)
                    setOnClickListener { step.run() }
                })
                card.addView(list)
            }
        }
        return card
    }

    private fun recentCard(): View {
        val card = card(dp(18), dp(6))
        card.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(8), 0)
            addView(text("Recent", 18f, t.ink, f.bold), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(ghost("See all") { go(RECENT) })
        })
        val latest = settings.recentLog().map(Recent::parse).firstOrNull { !it.failed }
        val body = column(dp(20), dp(4), dp(20), dp(14), gap = 6)
        if (latest == null) {
            body.addView(text("Nothing yet. Your dictations show up here.", 15f, t.ink2))
        } else {
            if (latest.isCommand) {
                body.addView(text("“${latest.heard}”", 14f, t.ink2))
                body.addView(text(latest.result, 16f, t.ink).apply { setLineSpacing(0f, 1.1f) })
            } else {
                body.addView(text(latest.heard, 14f, t.ink2).apply { paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG })
                body.addView(text(latest.result, 16f, t.ink).apply { setLineSpacing(0f, 1.1f) })
            }
            body.addView(text(meta(latest), 13f, t.ink2))
        }
        card.addView(body)
        return card
    }

    private fun navRow(title: String, summary: String, target: String, extra: View? = null, summaryColor: Int = t.ink2): View {
        val selected = twoPane && page == target
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(14), dp(12), dp(10), dp(12))
            if (selected) background = rounded(t.field, dp(18).toFloat())
            val labels = stack(text(title, 16f, t.ink, f.semibold), text(summary, 14f, summaryColor))
            extra?.let { labels.addView(it, LinearLayout.LayoutParams(dp(200), dp(4)).apply { topMargin = dp(8) }) }
            addView(labels, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(IconView(this@MainActivity, IconView.Kind.RIGHT, t.ink2), LinearLayout.LayoutParams(dp(20), dp(20)))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { marginStart = dp(6); marginEnd = dp(6) }
            ripple(this, 18)
            isSelected = selected
            setOnClickListener { go(target) }
        }
    }

    private fun engineName() = when (settings.engine) {
        Settings.ENGINE_GEMINI -> "Gemini"
        Settings.ENGINE_GROQ -> "Groq"
        else -> "Local only"
    }

    private fun engineSummary(asr: LocalAsr.Status, gemma: LocalAsr.Status): String {
        val local = settings.engine == Settings.ENGINE_LOCAL
        val model = when {
            local && asr is LocalAsr.Status.Downloading -> "Parakeet downloading, ${percent(asr)}%"
            local && asr != LocalAsr.Status.Ready -> "Parakeet needs downloading"
            gemma is LocalAsr.Status.Downloading -> "Gemma downloading, ${percent(gemma)}%"
            gemma == LocalAsr.Status.Ready -> if (settings.localAi) "Gemma on" else "Gemma off"
            else -> "No Gemma"
        }
        return "${engineName()} · $model"
    }

    private fun bubbleSummary() = (if (settings.bubbleShape == "bar") "Wide bar" else "Round bubble") + " · " + sizeWord().lowercase()

    private fun snoozeSummary(): String {
        settings.snoozedUntil()?.let { return "Hidden until ${clock(it)}" }
        return if (settings.nightSnooze) "Night snooze ${clockOfDay(settings.nightStart)} – ${clockOfDay(settings.nightEnd)}" else "Off"
    }

    private fun wordsSummary(): String {
        val words = dictionaryWords().size
        val keys = listOf(settings.apiKey, settings.groqKey).count { it.isNotBlank() }
        return "$words ${if (words == 1) "word" else "words"} · " + when (keys) {
            0 -> "no keys"
            1 -> "1 key added"
            else -> "$keys keys added"
        }
    }

    // ---- Detail pages ----

    private fun detail(title: String, body: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            if (!twoPane) addView(FrameLayout(this@MainActivity).apply {
                contentDescription = "Back"
                addView(IconView(this@MainActivity, IconView.Kind.BACK, t.ink), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                ripple(this, 24)
                setOnClickListener { go(HOME) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(text(title, 22f, t.ink, f.bold).apply {
                letterSpacing = -0.01f
                setPadding(dp(8), 0, 0, 0)
                isAccessibilityHeading = true
            })
        })
        addView(body)
    }

    private fun enginePage(asr: LocalAsr.Status, gemma: LocalAsr.Status): View {
        val col = column(dp(16), dp(4), dp(16), dp(32), gap = 12)
        col.addView(section("Speech engine", top = 8))
        val group = column(0, 0, 0, 0, gap = 10)
        group.addView(engineCard(Settings.ENGINE_LOCAL, "Local only", "Default", cloud = false, "Nothing leaves your phone. Works offline. English and 24 European languages."))
        group.addView(engineCard(Settings.ENGINE_GEMINI, "Gemini", "Sends audio", cloud = true, "Audio goes to Google. Handles Punjabi and mixed languages. Needs your API key."))
        group.addView(engineCard(Settings.ENGINE_GROQ, "Groq", "Sends audio", cloud = true, "Audio goes to Groq. Fast Whisper transcription. Needs your API key."))
        col.addView(group)
        if (settings.engine != Settings.ENGINE_LOCAL) settings.engineProblem(this)?.let { problem ->
            col.addView(text(problem.removePrefix("Tap here to ").replaceFirstChar { it.uppercase() }, 15f, t.attT, f.bold).apply {
                background = rounded(t.attSoft, dp(22).toFloat())
                setPadding(dp(18), dp(14), dp(18), dp(14))
                minimumHeight = dp(48)
                ripple(this, 22)
                setOnClickListener { go(WORDS) }
            })
        }

        col.addView(section("On this phone", top = 14))
        col.addView(card(dp(6), dp(6)).apply {
            addView(modelRow("Parakeet", "Hears your speech", LocalAsr.TOTAL_BYTES, asr, ::onParakeetButton) {
                LocalAsr.delete(applicationContext) { runOnUiThread { render(); toast("Parakeet deleted") } }
            })
            addView(modelRow("Gemma 4 E2B", "Understands commands", LocalLlm.SIZE, gemma, ::onGemmaButton) {
                LocalLlm.delete(applicationContext) { runOnUiThread { render(); toast("Gemma deleted") } }
            })
        })

        val ready = gemma == LocalAsr.Status.Ready
        val local = settings.engine == Settings.ENGINE_LOCAL
        col.addView(card(dp(6), dp(6)).apply {
            addView(switchRow(
                "Understand commands the rules miss",
                if (ready) "Gemma steps in when a command isn't clear. It asks before acting." else "Download Gemma to turn this on.",
                settings.localAi, ready,
            ) { settings.localAi = it })
            addView(switchRow(
                "Polish dictation",
                when {
                    !ready -> "Download Gemma to turn this on."
                    !local -> "Works with Local only."
                    else -> "Tidies punctuation and drops filler words before typing. Adds about a second."
                },
                settings.localPolish, ready && local,
            ) { settings.localPolish = it })
        })

        col.addView(section("Things to say to the side button", top = 14))
        col.addView(card(dp(16), dp(16)).apply {
            addView(text(COMMAND_EXAMPLES, 15f, t.ink).apply { setPadding(dp(20), 0, dp(20), 0); setLineSpacing(dp(4).toFloat(), 1f) })
            addView(text("Texts and calls open Messages or Phone filled in; you tap Send or Call there.", 14f, t.ink2)
                .apply { setPadding(dp(20), dp(12), dp(20), 0) })
        })
        return col
    }

    private fun engineCard(id: String, title: String, tag: String, cloud: Boolean, detail: String): View {
        val on = settings.engine == id
        val radio = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), t.ink) }
            addView(View(this@MainActivity).apply {
                background = rounded(t.ink, dp(6).toFloat())
                alpha = if (on) 1f else 0f
            }, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER))
        }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(title, 16f, t.ink, f.bold))
            addView(text(tag, 12f, if (cloud) t.attT else t.accT, f.bold).apply {
                background = rounded(if (cloud) t.attSoft else t.accSoft, dp(999).toFloat())
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        return LinearLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(t.card)
                if (on) setStroke(dp(2), t.ink)
            }
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(radio, LinearLayout.LayoutParams(dp(22), dp(22)).apply { topMargin = dp(1) })
            addView(stack(head, text(detail, 14f, t.ink2).apply { setPadding(0, dp(4), 0, 0); setLineSpacing(0f, 1.1f) })
                .apply { setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = on
                }
            }
            ripple(this, 22)
            setOnClickListener { settings.engine = id; render() }
        }
    }

    /** One on-device model: its size and role, then Ready, a Download button, or the download's progress. */
    private fun modelRow(
        name: String, role: String, bytes: Long, status: LocalAsr.Status, onButton: () -> Unit, delete: () -> Unit,
    ): View {
        val row = column(dp(20), dp(14), dp(12), dp(14), gap = 10)
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48) }
        head.addView(stack(text(name, 16f, t.ink, f.semibold), text("$role · ${size(bytes)}", 14f, t.ink2)),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        when (status) {
            LocalAsr.Status.Ready -> head.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(8), 0, dp(8), 0)
                addView(IconView(this@MainActivity, IconView.Kind.CHECK, t.accT, 3f), LinearLayout.LayoutParams(dp(18), dp(18)))
                addView(text("Ready", 14f, t.accT, f.bold).apply { setPadding(dp(6), 0, 0, 0) })
                contentDescription = "$name ready. Remove from this phone"
                ripple(this, 24)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Remove $name?")
                        .setMessage("It frees ${size(bytes)}. You can download it again later.")
                        .setPositiveButton("Remove") { _, _ -> delete() }
                        .setNegativeButton("Keep", null)
                        .show()
                }
            })
            LocalAsr.Status.Installing -> Unit
            is LocalAsr.Status.Downloading -> head.addView(pill("Cancel", false, "Cancel $name download", onButton))
            is LocalAsr.Status.Failed -> head.addView(pill("Try again", false, "Download $name again", onButton))
            LocalAsr.Status.Missing -> head.addView(pill("Download", false, "Download $name", onButton))
        }
        row.addView(head)
        when (status) {
            is LocalAsr.Status.Downloading -> {
                row.addView(bar(percent(status), 8).apply {
                    contentDescription = "$name download, ${percent(status)}%"
                }, LinearLayout.LayoutParams(MATCH_PARENT, dp(8)).apply { marginEnd = dp(8) })
                row.addView(text(
                    if (status.waitingForWifi) "Waiting for Wi-Fi" else "${progressSize(status.done, status.total)} · keeps going if you leave",
                    13f, t.ink2,
                ))
            }
            LocalAsr.Status.Installing -> row.addView(text("Checking and installing…", 13f, t.ink2))
            is LocalAsr.Status.Failed -> row.addView(text("Download failed: ${status.reason}", 13f, t.attT))
            LocalAsr.Status.Missing -> row.addView(text("One-time download over Wi-Fi.", 13f, t.ink2))
            LocalAsr.Status.Ready -> Unit
        }
        return row
    }

    private fun onParakeetButton() {
        when (LocalAsr.refresh(this)) {
            is LocalAsr.Status.Downloading -> LocalAsr.cancelDownloads(applicationContext)
            LocalAsr.Status.Installing, LocalAsr.Status.Ready -> Unit
            else -> LocalAsr.download(applicationContext)
        }
        render()
    }

    private fun onGemmaButton() {
        when (LocalLlm.refresh(this)) {
            is LocalAsr.Status.Downloading -> LocalLlm.cancelDownload(applicationContext)
            LocalAsr.Status.Installing, LocalAsr.Status.Ready -> Unit
            else -> LocalLlm.download(applicationContext)
        }
        render()
    }

    private fun bubblePage(): View {
        val col = column(dp(16), dp(4), dp(16), dp(32), gap = 16)
        val bar = settings.bubbleShape == "bar"

        val box = FrameLayout(this).apply {
            background = rounded(t.field, dp(24).toFloat())
            clipToOutline = true
            contentDescription = "Preview"
        }
        box.addView(EditText(this).apply {
            hint = "Tap here to try it"
            textSize = 15f
            typeface = f.regular
            setTextColor(t.ink)
            setHintTextColor(t.ink2)
            background = rounded(t.card, dp(16).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(20), dp(24), dp(20), 0) })
        val bubble = BubbleView(this) { 0f }.apply {
            this.bar = bar
            label = if (bar) "Hold to talk · tap to latch" else ""
        }
        box.addView(bubble)
        preview = bubble
        previewBox = box
        col.addView(box)
        sizePreview()

        col.addView(section("Shape", top = 4))
        col.addView(LinearLayout(this).apply {
            background = rounded(t.field, dp(999).toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(segment("Round bubble", !bar) { settings.bubbleShape = "circle"; render() }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(segment("Wide bar", bar) { settings.bubbleShape = "bar"; render() },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        })
        col.addView(note(if (bar) "Shows its labels. Easier to hit with a thumb." else "Small and out of the way. Drag it anywhere, even onto the keyboard."))

        val value = text(sizeWord(), 14f, t.ink2)
        sizeValue = value
        col.addView(card(dp(16), dp(16)).apply {
            addView(LinearLayout(this@MainActivity).apply {
                setPadding(dp(20), 0, dp(20), 0)
                addView(text("Size", 16f, t.ink, f.semibold), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(value)
            })
            addView(SeekBar(this@MainActivity).apply {
                max = Settings.BUBBLE_MAX - Settings.BUBBLE_MIN
                progress = settings.bubbleSize - Settings.BUBBLE_MIN
                progressTintList = ColorStateList.valueOf(t.swOn)
                progressBackgroundTintList = ColorStateList.valueOf(t.off)
                thumbTintList = ColorStateList.valueOf(t.swOn)
                contentDescription = "Bubble size"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, v: Int, fromUser: Boolean) {
                        if (fromUser) settings.bubbleSize = Settings.BUBBLE_MIN + v
                        stateDescription = sizeWord()
                        sizePreview()
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { marginStart = dp(4); marginEnd = dp(4) })
            addView(LinearLayout(this@MainActivity).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(dp(20), 0, dp(20), 0)
                addView(text("Small", 13f, t.ink2), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(text("Large", 13f, t.ink2))
            })
        })
        col.addView(note("Hold to talk, or tap to start and tap again to finish. It remembers where you drop it."))
        col.addView(ghost("Move it back above the keyboard") {
            settings.resetBubblePosition()
            toast("Bubble moved back above the keyboard")
        })
        return col
    }

    private fun sizePreview() {
        val bubble = preview ?: return
        val box = previewBox ?: return
        val side = dp(settings.bubbleSize) + 2 * bubble.inset
        bubble.layoutParams = FrameLayout.LayoutParams(if (bubble.bar) MATCH_PARENT else side, side, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(if (bubble.bar) dp(12) else 0, 0, dp(12), dp(20))
        }
        box.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, maxOf(dp(240), side + dp(110)))
        sizeValue?.text = sizeWord()
    }

    private fun snoozePage(): View {
        val col = column(dp(16), dp(4), dp(16), dp(32), gap = 16)
        val now = System.currentTimeMillis()
        val until = settings.snoozedUntil(now)
        val byNight = until != null && settings.nightUntil(now) == until
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(if (until != null) t.attSoft else t.card, dp(22).toFloat())
            setPadding(dp(18), dp(16), dp(12), dp(16))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            val title = when {
                until == null -> "Bubble is showing"
                byNight -> "Night snooze until ${clock(until)}"
                else -> "Bubble hidden until ${clock(until)}"
            }
            addView(stack(
                text(title, 16f, if (until != null) t.attT else t.ink, f.bold),
                text(if (until != null) "Side button commands still work." else "Pick a time to hide it.", 14f, t.ink2).apply { setPadding(0, dp(2), 0, 0) },
            ), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (until != null) addView(pill("Show now", true, "Show the bubble now") {
                settings.endSnooze()
                render()
                toast("The bubble is back")
            })
        })

        col.addView(section("Hide the bubble for", top = 4))
        val active = settings.snoozeUntil.takeIf { Snooze.isActive(now, it) }?.let { settings.snoozeMinutes }
        col.addView(LinearLayout(this).apply {
            Snooze.MINUTES.forEachIndexed { i, minutes ->
                val on = active == minutes
                addView(text(Snooze.label(minutes), 15f, if (on) t.onBtn else t.ink, f.bold).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        cornerRadius = dp(999).toFloat()
                        setColor(if (on) t.btn else 0)
                        if (!on) setStroke(dpf(1.5f).toInt(), t.line2)
                    }
                    isSelected = on
                    contentDescription = "Hide for ${Snooze.label(minutes)}"
                    ripple(this, 999)
                    setOnClickListener {
                        if (on) settings.endSnooze() else {
                            settings.snoozeMinutes = minutes
                            settings.snooze()
                        }
                        render()
                    }
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i > 0) marginStart = dp(8) })
            }
        })
        col.addView(note("Dragging the bubble to the bottom of the screen hides it for ${Snooze.label(settings.snoozeMinutes)}, the length you picked last."))

        val nightOn = settings.nightSnooze
        col.addView(card(dp(6), dp(12)).apply {
            addView(switchRow("Night snooze", "Hide the bubble every night.", nightOn, true) { settings.nightSnooze = it; render() })
            addView(LinearLayout(this@MainActivity).apply {
                setPadding(dp(20), 0, dp(20), 0)
                alpha = if (nightOn) 1f else 0.45f
                addView(timeTile("From", settings.nightStart, nightOn) { settings.nightStart = it }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(timeTile("To", settings.nightEnd, nightOn) { settings.nightEnd = it },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            })
        })
        return col
    }

    private fun timeTile(label: String, minute: Int, enabled: Boolean, pick: (Int) -> Unit): View =
        stack(text(label, 13f, t.ink2), text(clockOfDay(minute), 18f, t.ink, f.bold)).apply {
            background = rounded(t.field, dp(18).toFloat())
            minimumHeight = dp(64)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            contentDescription = "$label ${clockOfDay(minute)}. Change"
            isEnabled = enabled
            ripple(this, 18)
            setOnClickListener {
                TimePickerDialog(this@MainActivity, { _, h, m -> pick(h * 60 + m); render() },
                    minute / 60, minute % 60, DateFormat.is24HourFormat(this@MainActivity)).show()
            }
        }

    private fun wordsPage(): View {
        val col = column(dp(16), dp(4), dp(16), dp(32), gap = 12)
        col.addView(section("Personal dictionary", top = 8))
        val words = dictionaryWords()
        val input = EditText(this).apply {
            hint = "Add a word"
            setText(draft)
            textSize = 15f
            typeface = f.regular
            setTextColor(t.ink)
            setHintTextColor(t.ink2)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = rounded(t.field, dp(999).toFloat())
            setPadding(dp(18), 0, dp(18), 0)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable) { draft = s.toString() }
                override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) = Unit
            })
        }
        val add = {
            val word = draft.trim().trimEnd(',')
            if (word.isNotEmpty() && word !in words) setDictionaryWords(words + word)
            draft = ""
            render()
        }
        input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { add(); true } else false }
        col.addView(card(dp(16), dp(16)).apply {
            addView(note("Names and words ${Settings.APP_NAME} should always spell your way.").apply { setPadding(dp(20), 0, dp(20), 0) })
            addView(FlowLayout(this@MainActivity, dp(8)).apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                for (word in words) addView(LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(t.field, dp(999).toFloat())
                    setPadding(dp(14), 0, 0, 0)
                    minimumHeight = dp(40)
                    addView(text(word, 15f, t.ink, f.semibold))
                    addView(FrameLayout(this@MainActivity).apply {
                        contentDescription = "Remove $word"
                        addView(IconView(this@MainActivity, IconView.Kind.CLOSE, t.ink2), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
                        ripple(this, 20)
                        setOnClickListener { setDictionaryWords(words - word); render() }
                    }, LinearLayout.LayoutParams(dp(40), dp(40)))
                })
            })
            addView(LinearLayout(this@MainActivity).apply {
                setPadding(dp(16), 0, dp(16), 0)
                addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(text("Add", 15f, t.onBtn, f.bold).apply {
                    gravity = Gravity.CENTER
                    background = rounded(t.btn, dp(999).toFloat())
                    setPadding(dp(20), 0, dp(20), 0)
                    ripple(this, 999)
                    setOnClickListener { add() }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(48)).apply { marginStart = dp(8) })
            })
        })

        col.addView(section("Fix what it hears", top = 14))
        val replace = EditText(this).apply {
            setText(settings.replaceText)
            hint = "super base = Supabase"
            textSize = 15f
            typeface = f.regular
            setTextColor(t.ink)
            setHintTextColor(t.ink2)
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            background = rounded(t.field, dp(18).toFloat())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        col.addView(card(dp(16), dp(16)).apply {
            addView(note("One per line: what it hears = what to type.").apply { setPadding(dp(20), 0, dp(20), dp(12)) })
            addView(replace, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { marginStart = dp(16); marginEnd = dp(16) })
            addView(FrameLayout(this@MainActivity).apply {
                setPadding(dp(12), dp(8), dp(12), 0)
                addView(pill("Save", true, "Save fixes") {
                    settings.replaceText = replace.text.toString()
                    toast("Saved")
                }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END))
            })
        })

        col.addView(section("API keys", top = 14))
        col.addView(card(dp(6), dp(6)).apply {
            addView(keyRow("Gemini", settings.apiKey, "aistudio.google.com/apikey") { settings.apiKey = it })
            addView(keyRow("Groq", settings.groqKey, "console.groq.com") { settings.groqKey = it })
            addView(settingRow("Gemini model", settings.model, "Change") {
                ask("Gemini model", settings.model, password = false) { settings.model = it }
            })
        })
        col.addView(note("Keys stay on this phone. They're only used if you pick that engine."))
        return col
    }

    private fun keyRow(name: String, key: String, where: String, save: (String) -> Unit): View =
        settingRow(name, if (key.isBlank()) "Not added" else "Ends in ${key.takeLast(4)}", if (key.isBlank()) "Add" else "Change") {
            ask("$name API key", "", password = true, message = "Get a free key at $where.") { save(it) }
        }

    private fun settingRow(title: String, value: String, action: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(20), dp(12), dp(12), dp(12))
        addView(stack(text(title, 16f, t.ink, f.semibold), text(value, 14f, t.ink2).apply { fontFeatureSettings = "tnum" }),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(pill(action, false, "$action $title", onClick))
    }

    /** A small dialog with one text field. Blank input changes nothing. */
    private fun ask(title: String, current: String, password: Boolean, message: String? = null, save: (String) -> Unit) {
        val field = EditText(this).apply {
            setText(current)
            isSingleLine = true
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) })
            .setPositiveButton("Save") { _, _ ->
                val v = field.text.toString().trim()
                if (v.isNotEmpty()) { save(v); render(); toast("Saved") }
            }
            .setNegativeButton("Cancel", null)
            .show()
        field.requestFocus()
    }

    private fun recentPage(): View {
        val col = column(dp(16), dp(4), dp(16), dp(32), gap = 12)
        col.addView(note("What ${Settings.APP_NAME} heard, and what it typed or did. The last 30, kept only on this phone."))
        val entries = settings.recentLog().map(Recent::parse)
        if (entries.isEmpty()) {
            col.addView(card(dp(18), dp(18)).apply {
                addView(text("Nothing yet. Your dictations show up here.", 15f, t.ink2).apply { setPadding(dp(20), 0, dp(20), 0) })
            })
            return col
        }
        col.addView(card(dp(4), dp(4)).apply {
            entries.forEachIndexed { i, r ->
                if (i > 0) addView(View(this@MainActivity).apply { setBackgroundColor(t.line) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
                val item = column(dp(20), dp(14), dp(20), dp(14), gap = 6)
                if (r.failed) {
                    item.addView(text(r.note, 14f, t.attT).apply { setTextIsSelectable(true) })
                } else {
                    item.addView(pair("Heard", r.heard, 14f, t.ink2, t.ink2))
                    item.addView(pair(if (r.isCommand) "Did" else "Typed", r.result, 16f, t.ink, t.accT))
                }
                item.addView(text(meta(r), 13f, t.ink2).apply { if (!r.failed) setPadding(dp(62), 0, 0, 0) })
                addView(item)
            }
        })
        col.addView(ghost("Clear history") {
            settings.clearLog()
            render()
        })
        return col
    }

    private fun pair(label: String, value: String, size: Float, color: Int, labelColor: Int) = LinearLayout(this).apply {
        addView(text(label, 12f, labelColor, f.bold).apply { setPadding(0, dp(3), 0, 0) }, LinearLayout.LayoutParams(dp(52), WRAP_CONTENT))
        addView(text(value, size, color).apply {
            setLineSpacing(0f, 1.1f)
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
    }

    // ---- Values ----

    private fun dictionaryWords() = settings.biasText.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun setDictionaryWords(words: List<String>) {
        settings.biasText = words.joinToString(", ")
    }

    /** The bubble's size in words: the slider runs from [Settings.BUBBLE_MIN] to [Settings.BUBBLE_MAX] dp. */
    private fun sizeWord(): String {
        val f = (settings.bubbleSize - Settings.BUBBLE_MIN).toFloat() / (Settings.BUBBLE_MAX - Settings.BUBBLE_MIN)
        return listOf("Smallest", "Small", "Medium", "Large", "Largest")[(f * 5).toInt().coerceIn(0, 4)]
    }

    private fun meta(r: Recent): String {
        val ago = stampMillis(r.stamp)?.let {
            DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        } ?: r.stamp
        return if (r.source.isEmpty()) ago else "${r.source} · $ago"
    }

    /** The log's stamps have no year ("Oct 10 14:02:11"): take this year's, or last year's if that's in the future. */
    private fun stampMillis(stamp: String): Long? = runCatching {
        val parsed = SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault()).parse(stamp) ?: return null
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { time = parsed; set(Calendar.YEAR, now.get(Calendar.YEAR)) }
        if (c.after(now)) c.add(Calendar.YEAR, -1)
        c.timeInMillis
    }.getOrNull()

    private fun percent(s: LocalAsr.Status.Downloading) = if (s.total > 0) (s.done * 100 / s.total).toInt() else 0

    private fun progressOf(s: LocalAsr.Status) = (s as? LocalAsr.Status.Downloading)?.let(::percent)

    private fun size(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"

    private fun progressSize(done: Long, total: Long) =
        if (total >= 1_000_000_000) "%.1f of %.1f GB".format(done / 1e9, total / 1e9) else "${done / 1_000_000} of ${total / 1_000_000} MB"

    private fun clock(ms: Long): String = DateFormat.getTimeFormat(this).format(java.util.Date(ms))

    private fun clockOfDay(minute: Int): String =
        clock(java.time.LocalDate.now().atTime(minute / 60, minute % 60).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---- Building blocks ----

    private fun scroller() = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    private fun column(l: Int, top: Int, r: Int, bottom: Int, gap: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(l, top, r, bottom)
        if (gap > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(0, dp(gap)) }
        }
    }

    private fun stack(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        views.forEach(::addView)
    }

    private fun card(top: Int, bottom: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(t.card, dp(24).toFloat())
        setPadding(0, top, 0, bottom)
    }

    private fun section(s: String, top: Int) = text(s, 14f, t.ink2, f.bold).apply {
        setPadding(dp(4), dp(top), dp(4), 0)
        isAccessibilityHeading = true
    }

    private fun note(s: String) = text(s, 14f, t.ink2).apply {
        setPadding(dp(4), 0, dp(4), 0)
        setLineSpacing(0f, 1.1f)
    }

    private fun text(s: CharSequence, size: Float, color: Int, face: Typeface = f.regular) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = face
    }

    private fun styled(s: String, color: Int, face: Typeface) = android.text.SpannableString(s).apply {
        setSpan(android.text.style.ForegroundColorSpan(color), 0, s.length, 0)
        setSpan(android.text.style.TypefaceSpan(face), 0, s.length, 0)
    }

    /** A pill button inside a 48 dp touch target: filled ([Look.btn]) for the main action, outlined otherwise. */
    private fun pill(label: String, filled: Boolean, description: String? = null, onClick: () -> Unit): View {
        val inner = text(label, 15f, if (filled) t.onBtn else t.ink, f.bold).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                if (filled) setColor(t.btn) else setStroke(dpf(1.5f).toInt(), t.line2)
            }
            setPadding(dp(18), dp(10), dp(18), dp(10))
            ripple(this, 999)
            isClickable = false
            isFocusable = false
            isDuplicateParentStateEnabled = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return FrameLayout(this).apply {
            minimumHeight = dp(48)
            setPadding(dp(4), 0, dp(4), 0)
            addView(inner, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            contentDescription = description ?: label
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    /** A text button in the accent colour (See all, Clear history). */
    private fun ghost(label: String, onClick: () -> Unit): View = FrameLayout(this).apply {
        minimumHeight = dp(48)
        setPadding(dp(8), 0, dp(8), 0)
        addView(text(label, 15f, t.accT, f.bold).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        contentDescription = label
        ripple(this, 24)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
    }

    private fun segment(label: String, on: Boolean, onClick: () -> Unit) = text(label, 15f, if (on) t.ink else t.ink2, f.bold).apply {
        gravity = Gravity.CENTER
        if (on) {
            background = rounded(t.card, dp(999).toFloat())
            elevation = dp(1).toFloat()
        }
        isSelected = on
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.RadioButton::class.java.name
                info.isCheckable = true
                info.isChecked = on
            }
        }
        setOnClickListener { onClick() }
    }

    private fun switchRow(title: String, sub: String, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit): View {
        val toggle = Toggle(this, t).apply { checked = on }
        var state = on
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(72)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            addView(stack(text(title, 16f, t.ink, f.semibold), text(sub, 14f, t.ink2).apply { setPadding(0, dp(2), 0, 0); setLineSpacing(0f, 1.1f) }),
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(16) })
            addView(toggle)
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.45f
            actAsSwitch { state }
            ripple(this, 0)
            setOnClickListener {
                state = !state
                toggle.checked = state
                onChange(state)
            }
        }
    }

    /** A thin progress bar: [percent] of it in the accent colour. */
    private fun bar(percent: Int, height: Int): View = FrameLayout(this).apply {
        background = rounded(t.field, dpf(height / 2f))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val fill = View(this@MainActivity).apply { background = rounded(t.accT, dpf(height / 2f)) }
        addView(fill)
        addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = v.width * percent.coerceIn(0, 100) / 100
            if (fill.layoutParams.width != w) fill.post { fill.layoutParams = FrameLayout.LayoutParams(w, MATCH_PARENT); fill.requestLayout() }
        }
    }

    private fun ripple(view: View, radiusDp: Int) {
        val mask = rounded(0xFF000000.toInt(), dp(radiusDp).toFloat())
        view.foreground = RippleDrawable(ColorStateList.valueOf((t.ink and 0x00FFFFFF) or 0x1F000000), null, mask)
        view.isClickable = true
        view.isFocusable = true
    }

    private fun dpf(v: Float) = v * resources.displayMetrics.density

    private companion object {
        const val HOME = "home"
        const val ENGINE = "engine"
        const val BUBBLE = "bubble"
        const val SNOOZE = "snooze"
        const val WORDS = "words"
        const val RECENT = "recent"
        const val TWO_PANE_DP = 720

        const val COMMAND_EXAMPLES = "“Open Spotify”\n“Set a timer for 10 minutes”\n“Wake me up at 6:30”\n" +
            "“Turn on the torch”\n“Pause” · “Next song”\n“Text Sam I'm running late”\n“Call Mum”\n" +
            "“Navigate to the airport”\n“Add dentist tomorrow at 3 pm to my calendar”"
    }
}

/** Lays children out in rows, wrapping to the next row when one is full (the dictionary's word chips). */
class FlowLayout(context: android.content.Context, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            measureChild(c, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > width) { x = 0; y += rowH + gap; rowH = 0 }
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > width) { x = 0; y += rowH + gap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}
