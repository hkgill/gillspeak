package dev.hkgill.gillspeak

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
import android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS
import android.provider.Settings.Secure
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Setup (microphone, bubble, keyboard), a test field, the speech engine, bubble settings, keys and dictionary, recent dictations. */
class MainActivity : Activity() {
    private data class Colors(
        val bg: Int, val card: Int, val text: Int, val dim: Int, val accent: Int, val ok: Int, val field: Int,
    )

    private class SetupRow(val view: View, val icon: TextView, val subtitle: TextView)
    private class EngineRow(val id: String, val view: LinearLayout, val dot: TextView)

    private lateinit var settings: Settings
    private lateinit var c: Colors
    private var night = false
    private lateinit var micRow: SetupRow
    private lateinit var bubbleRow: SetupRow
    private lateinit var keyboardRow: SetupRow
    private lateinit var keyField: EditText
    private lateinit var groqField: EditText
    private lateinit var engineRows: List<EngineRow>
    private lateinit var modelStatus: TextView
    private lateinit var modelButton: TextView
    private lateinit var roundChip: TextView
    private lateinit var barChip: TextView
    private lateinit var preview: BubbleView
    private lateinit var sizeLabel: TextView
    private lateinit var log: LinearLayout
    private lateinit var snoozeCard: View
    private lateinit var snoozeStatus: TextView
    private lateinit var snoozeChips: List<Pair<Int, TextView>>
    private lateinit var nightSwitch: android.widget.Switch
    private lateinit var nightFrom: TextView
    private lateinit var nightTo: TextView
    private lateinit var nightTimes: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        c = if (night) {
            Colors(Brand.NIGHT, Brand.NIGHT_SURFACE, Brand.PAPER, 0xFFA9ADB6.toInt(), Brand.TEAL, Brand.TEAL, 0xFF2A2D35.toInt())
        } else {
            Colors(Brand.PAPER, Brand.SURFACE, Brand.INK, Brand.INK_2, Brand.TEAL_TEXT, Brand.TEAL_TEXT, Brand.FIELD)
        }
        actionBar?.hide()
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (night) 0 else lightBars, lightBars)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        val badge = text("Local only", 13f, if (night) Brand.TEAL_SOFT else Brand.TEAL_DEEP, bold = true).apply {
            background = rounded(if (night) 0xFF14302C.toInt() else Brand.TEAL_SOFT, dp(999).toFloat())
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        column.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(MarkView(this@MainActivity), LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(text(Settings.APP_NAME, 28f, c.text, bold = true).apply { setPadding(dp(10), 0, 0, 0) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(badge)
        })
        column.addView(text("Talk, and clean text lands where you type. Nothing leaves your phone.", 16f, c.dim).apply { setPadding(0, dp(10), 0, dp(20)) })

        micRow = setupRow("Microphone") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
        bubbleRow = setupRow("Floating bubble") { startActivity(Intent(ACTION_ACCESSIBILITY_SETTINGS)) }
        keyboardRow = setupRow("${Settings.APP_NAME} keyboard") {
            val ime = getSystemService(InputMethodManager::class.java)
            if (ime.enabledInputMethodList.any { it.packageName == packageName }) ime.showInputMethodPicker()
            else startActivity(Intent(ACTION_INPUT_METHOD_SETTINGS))
        }
        column.addView(card("Setup", micRow.view, divider(), bubbleRow.view, divider(), keyboardRow.view))

        snoozeStatus = text("", 15f, c.dim)
        snoozeCard = card(
            "Bubble is snoozed", snoozeStatus,
            primaryButton("End snooze now") {
                settings.endSnooze()
                refresh()
                Toast.makeText(this, "The bubble is back", Toast.LENGTH_SHORT).show()
            },
        )
        column.addView(snoozeCard)

        column.addView(card("Try it", field("Tap here, then use the bubble", lines = 3)))

        engineRows = listOf(
            engineRow(Settings.ENGINE_LOCAL, "Local only", "Default", cloud = false, "Nothing leaves your phone. Instant, works offline. English and 24 European languages."),
            engineRow(Settings.ENGINE_GEMINI, "Gemini", "Sends audio", cloud = true, "Optional cloud engine: your audio goes to Google. AI polish, Punjabi and mixed languages. Needs a Gemini key."),
            engineRow(Settings.ENGINE_GROQ, "Groq", "Sends audio", cloud = true, "Optional cloud engine: your audio goes to Groq. Fast Whisper transcription. Needs a free Groq key."),
        )
        modelStatus = text("", 14f, c.dim).apply { setPadding(0, dp(10), 0, dp(4)) }
        modelButton = text("", 15f, c.accent, bold = true).apply {
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { onModelButton() }
        }
        column.addView(card("Speech engine", *engineRows.map { it.view }.toTypedArray(), modelStatus, modelButton))

        roundChip = chip("Square") { settings.bubbleShape = "circle"; refresh() }
        barChip = chip("Wide bar") { settings.bubbleShape = "bar"; refresh() }
        val chips = LinearLayout(this).apply {
            background = rounded(c.field, dp(14).toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(roundChip, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(barChip, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        preview = BubbleView(this) { 0f }
        val previewBox = FrameLayout(this).apply {
            setPadding(0, dp(12), 0, dp(4))
            addView(preview)
        }
        sizeLabel = text("", 14f, c.dim)
        val slider = SeekBar(this).apply {
            max = Settings.BUBBLE_MAX - Settings.BUBBLE_MIN
            progress = settings.bubbleSize - Settings.BUBBLE_MIN
            progressTintList = ColorStateList.valueOf(c.accent)
            thumbTintList = ColorStateList.valueOf(c.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) settings.bubbleSize = Settings.BUBBLE_MIN + value
                    showBubblePreview()
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        val reset = text("Reset position", 15f, c.accent, bold = true).apply {
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener {
                settings.resetBubblePosition()
                Toast.makeText(this@MainActivity, "Bubble moved back above the keyboard", Toast.LENGTH_SHORT).show()
            }
        }
        snoozeChips = Snooze.MINUTES.map { minutes ->
            minutes to chip(Snooze.label(minutes)) { settings.snoozeMinutes = minutes; refresh() }
        }
        val snoozeRow = LinearLayout(this).apply {
            background = rounded(c.field, dp(14).toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
            snoozeChips.forEach { (_, chip) -> addView(chip, LinearLayout.LayoutParams(0, dp(40), 1f)) }
        }
        column.addView(card(
            "Bubble", chips, previewBox, sizeLabel, slider,
            text("Hold to talk, or tap to start and tap again to finish. Drag it anywhere, even onto the keyboard; it remembers the spot.", 14f, c.dim)
                .apply { setPadding(0, dp(8), 0, 0) },
            reset,
            label("Snooze for"), snoozeRow,
            text("Drag the bubble to the bottom of the screen and drop it on the target to hide it for this long.", 14f, c.dim)
                .apply { setPadding(0, dp(8), 0, 0) },
            nightRow(),
        ))

        keyField = field("", password = true)
        groqField = field("", password = true)
        val model = field("").apply { setText(settings.model) }
        val replace = field("super base = Supabase", lines = 3).apply { setText(settings.replaceText) }
        val bias = field("Supabase, Fedora").apply { setText(settings.biasText) }
        column.addView(card(
            "Keys and dictionary",
            label("Gemini API key (free at aistudio.google.com/apikey)"), keyField,
            label("Gemini model"), model,
            label("Groq API key (free at console.groq.com)"), groqField,
            label("Dictionary: one per line, spoken = Written"), replace,
            label("Preferred spellings, comma-separated"), bias,
            primaryButton("Save") {
                if (keyField.text.isNotBlank()) settings.apiKey = keyField.text.toString()
                if (groqField.text.isNotBlank()) settings.groqKey = groqField.text.toString()
                keyField.text.clear()
                groqField.text.clear()
                settings.model = model.text.toString()
                settings.replaceText = replace.text.toString()
                settings.biasText = bias.text.toString()
                refresh()
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            },
        ))

        log = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(card("Recent dictations", log))

        column.addView(text(
            "The bubble uses Android's accessibility service only to see when a keyboard and a text field are on " +
                "screen and to type your dictation into that field. It skips password fields and reads nothing else. " +
                "With Local only (the default) nothing leaves your phone. If you choose Gemini or Groq, your recorded audio " +
                "is sent to that service. Dictations are kept only on this phone.",
            13f, c.dim,
        ).apply { setPadding(dp(4), dp(8), dp(4), 0) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(c.bg)
            addView(column)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })

    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        showRow(micRow, mic, "1", if (mic) "Allowed" else "Tap to allow")

        val services = Secure.getString(contentResolver, Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val bubble = ComponentName(this, BubbleService::class.java)
        val bubbleOn = services.split(':').any { it == bubble.flattenToString() || it == bubble.flattenToShortString() }
        showRow(bubbleRow, bubbleOn, "2", if (bubbleOn) "On. It appears whenever you type" else "Tap, then turn on ${getString(R.string.bubble_label)}")

        val keyboardOn = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }
        showRow(keyboardRow, keyboardOn, "3", if (keyboardOn) "On. Tap to switch keyboards" else "Optional: a full-screen mic keyboard")

        val bar = settings.bubbleShape == "bar"
        styleChip(roundChip, !bar)
        styleChip(barChip, bar)
        showBubblePreview()
        showSnooze()

        keyField.hint = if (settings.apiKey.isNotBlank()) "Saved. Type to replace" else "Paste your Gemini API key"
        groqField.hint = if (settings.groqKey.isNotBlank()) "Saved. Type to replace" else "Paste your Groq API key"
        showEngine()
        showLog()
    }

    private fun engineRow(id: String, title: String, tag: String, cloud: Boolean, detail: String): EngineRow {
        val dot = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(title, 16f, c.text, bold = true))
                addView(text(tag, 12f, if (cloud) Brand.AMBER_TEXT else Brand.TEAL_DEEP, bold = true).apply {
                    background = rounded(if (cloud) Brand.AMBER_SOFT else Brand.TEAL_SOFT, dp(999).toFloat())
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
            })
            addView(text(detail, 14f, c.dim).apply { setPadding(0, dp(4), 0, 0) })
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
            addView(dot, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(labels, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            setOnClickListener {
                settings.engine = id
                refresh()
            }
        }
        return EngineRow(id, row, dot)
    }

    private fun showEngine() {
        for (row in engineRows) {
            val on = row.id == settings.engine
            row.view.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (!on) c.card else if (night) 0xFF14302C.toInt() else 0xFFF1FAF8.toInt())
                setStroke(dp(if (on) 2 else 1), if (on) c.accent else if (night) 0xFF2A2D35.toInt() else Brand.LINE)
            }
            row.dot.text = if (on) "✓" else ""
            row.dot.background = rounded(if (on) c.accent else c.field, dp(11).toFloat())
        }
        val local = settings.engine == Settings.ENGINE_LOCAL
        modelStatus.visibility = if (local) View.VISIBLE else View.GONE
        modelButton.visibility = if (local) View.VISIBLE else View.GONE
        val mb = LocalAsr.TOTAL_BYTES / 1_000_000
        val status = LocalAsr.refresh(this)
        when (status) {
            LocalAsr.Status.Ready -> {
                modelStatus.text = "On-device model ready ✓ (Parakeet, $mb MB)"
                modelButton.text = "Delete model"
            }
            LocalAsr.Status.Missing -> {
                modelStatus.text = "Needs a one-time $mb MB download over Wi-Fi."
                modelButton.text = "Download model"
            }
            is LocalAsr.Status.Downloading -> {
                modelStatus.text = if (status.waitingForWifi) "Waiting for Wi-Fi to download ($mb MB)."
                else "Downloading… ${status.done * 100 / status.total}% of $mb MB. You can leave the app; it carries on."
                modelButton.text = "Cancel download"
            }
            LocalAsr.Status.Installing -> {
                modelStatus.text = "Checking and installing the model…"
                modelButton.text = ""
            }
            is LocalAsr.Status.Failed -> {
                modelStatus.text = "Download failed: ${status.reason}"
                modelButton.text = "Try again"
            }
        }
        // Keep the progress moving while this screen is open.
        handler.removeCallbacks(poll)
        if (local && (status is LocalAsr.Status.Downloading || status == LocalAsr.Status.Installing)) handler.postDelayed(poll, 1000)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val poll = Runnable { showEngine() }
    private val snoozeOver = Runnable { showSnooze() }

    override fun onPause() {
        handler.removeCallbacks(poll)
        handler.removeCallbacks(snoozeOver)
        super.onPause()
    }

    private fun onModelButton() {
        when (LocalAsr.refresh(this)) {
            LocalAsr.Status.Ready -> {
                modelButton.text = ""
                modelStatus.text = "Deleting…"
                LocalAsr.delete(applicationContext) {
                    runOnUiThread {
                        showEngine()
                        Toast.makeText(this, "On-device model deleted", Toast.LENGTH_SHORT).show()
                    }
                }
                return
            }
            is LocalAsr.Status.Downloading -> LocalAsr.cancelDownloads(applicationContext)
            LocalAsr.Status.Installing -> Unit
            else -> LocalAsr.download(applicationContext)
        }
        showEngine()
    }

    private fun showRow(row: SetupRow, done: Boolean, number: String, subtitle: String) {
        row.icon.text = if (done) "✓" else number
        row.icon.setTextColor(if (done) Color.WHITE else c.accent)
        row.icon.background = rounded(if (done) c.ok else c.field, dp(18).toFloat())
        row.subtitle.text = subtitle
    }

    private fun showSnooze() {
        for ((minutes, chip) in snoozeChips) styleChip(chip, minutes == settings.snoozeMinutes)
        nightSwitch.isChecked = settings.nightSnooze
        nightTimes.visibility = if (settings.nightSnooze) View.VISIBLE else View.GONE
        nightFrom.text = clockOfDay(settings.nightStart)
        nightTo.text = clockOfDay(settings.nightEnd)
        handler.removeCallbacks(snoozeOver)
        val now = System.currentTimeMillis()
        val snoozed = settings.snoozed(now)
        snoozeCard.visibility = if (snoozed) View.VISIBLE else View.GONE
        if (!snoozed) return
        val until = settings.snoozedUntil(now) ?: return
        val night = settings.nightUntil(now) == until
        snoozeStatus.text = (if (night) "Night snooze. " else "") + "It comes back at ${clock(until)}."
        handler.postDelayed(snoozeOver, until - now + 50) // hide this card when it does
    }

    private fun clock(ms: Long): String = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(ms))

    private fun clockOfDay(minute: Int): String =
        clock(java.time.LocalDate.now().atTime(minute / 60, minute % 60).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())

    /** "Snooze every night" with a switch, then From and To times that open a time picker. */
    private fun nightRow(): View {
        nightSwitch = android.widget.Switch(this).apply {
            text = "Snooze every night"
            textSize = 16f
            setTextColor(c.text)
            typeface = Typeface.DEFAULT_BOLD
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(c.accent, c.dim))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(c.accent, c.field))
            setOnCheckedChangeListener { _, on -> if (on != settings.nightSnooze) { settings.nightSnooze = on; refresh() } }
        }
        fun timeButton(pick: (Int) -> Unit, current: () -> Int) = text("", 16f, c.accent, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(c.field, dp(12).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                val m = current()
                android.app.TimePickerDialog(this@MainActivity, { _, h, min -> pick(h * 60 + min); refresh() },
                    m / 60, m % 60, android.text.format.DateFormat.is24HourFormat(this@MainActivity)).show()
            }
        }
        nightFrom = timeButton({ settings.nightStart = it }) { settings.nightStart }
        nightTo = timeButton({ settings.nightEnd = it }) { settings.nightEnd }
        nightTimes = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(text("From", 14f, c.dim).apply { setPadding(0, 0, dp(8), 0) })
            addView(nightFrom, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(text("to", 14f, c.dim).apply { setPadding(dp(12), 0, dp(8), 0) })
            addView(nightTo, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, 0)
            addView(nightSwitch, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(text("Hide the bubble at night, every night. End snooze now brings it back until the morning.", 14f, c.dim)
                .apply { setPadding(0, dp(4), 0, 0) })
            addView(nightTimes)
        }
    }

    private fun showBubblePreview() {
        val size = settings.bubbleSize
        val bar = settings.bubbleShape == "bar"
        sizeLabel.text = "Size: $size dp"
        preview.bar = bar
        preview.label = if (bar) "Hold to talk · tap to latch" else ""
        val side = dp(size) + 2 * preview.inset
        preview.layoutParams = FrameLayout.LayoutParams(if (bar) MATCH_PARENT else side, side, Gravity.CENTER)
    }

    private fun showLog() {
        log.removeAllViews()
        val entries = settings.recentLog()
        if (entries.isEmpty()) {
            log.addView(text("Nothing yet. Your dictations will show up here.", 15f, c.dim))
            return
        }
        entries.forEachIndexed { i, entry ->
            if (i > 0) log.addView(divider())
            val lines = entry.lines()
            log.addView(text(lines.first(), 12f, c.dim).apply { setPadding(0, dp(10), 0, dp(2)) })
            for (line in lines.drop(1).map { it.trim() }) {
                when {
                    line.startsWith("typed: ") -> log.addView(text(line.removePrefix("typed: "), 15f, c.text).apply { setTextIsSelectable(true) })
                    line.startsWith("heard: ") -> log.addView(text("Heard: " + line.removePrefix("heard: "), 13f, c.dim).apply {
                        setPadding(0, dp(2), 0, dp(10))
                        setTextIsSelectable(true)
                    })
                    else -> log.addView(text(line, 15f, c.text))
                }
            }
        }
    }

    // ---- Building blocks ----

    private fun card(title: String, vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c.card, dp(20).toFloat())
        setPadding(dp(16), dp(14), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) }
        addView(text(title, 17f, c.text, bold = true).apply { setPadding(0, 0, 0, dp(8)) })
        children.forEach(::addView)
    }

    private fun setupRow(title: String, onClick: () -> Unit): SetupRow {
        val icon = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitle = text("", 14f, c.dim)
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
            addView(text(title, 16f, c.text, bold = true))
            addView(subtitle)
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(labels, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(text("›", 24f, c.dim))
            val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
            foreground = getDrawable(ripple.resourceId)
            setOnClickListener { onClick() }
        }
        return SetupRow(row, icon, subtitle)
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.background = if (selected) rounded(c.card, dp(10).toFloat()) else null
        chip.elevation = if (selected) dp(1).toFloat() else 0f
        chip.setTextColor(if (selected) c.text else c.dim)
        chip.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun field(hint: String, lines: Int = 1, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        textSize = 15f
        setTextColor(c.text)
        setHintTextColor(c.dim)
        background = rounded(c.field, dp(12).toFloat())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        if (lines > 1) {
            minLines = lines
            gravity = Gravity.TOP or Gravity.START
        } else {
            isSingleLine = true
        }
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = rounded(c.accent, dp(14).toFloat())
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(16) }
        setOnClickListener { onClick() }
    }

    private fun label(s: String) = text(s, 13f, c.dim).apply { setPadding(0, dp(12), 0, dp(6)) }

    private fun divider() = View(this).apply {
        setBackgroundColor(c.field)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1))
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
}
