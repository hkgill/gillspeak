package dev.hkgill.murmur

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

/** Setup (microphone, bubble, keyboard), a test field, bubble and Gemini settings, and recent dictations. */
class MainActivity : Activity() {
    private data class Colors(
        val bg: Int, val card: Int, val text: Int, val dim: Int, val accent: Int, val ok: Int, val field: Int,
    )

    private class SetupRow(val view: View, val icon: TextView, val subtitle: TextView)

    private lateinit var settings: Settings
    private lateinit var c: Colors
    private lateinit var micRow: SetupRow
    private lateinit var bubbleRow: SetupRow
    private lateinit var keyboardRow: SetupRow
    private lateinit var keyField: EditText
    private lateinit var roundChip: TextView
    private lateinit var barChip: TextView
    private lateinit var preview: BubbleView
    private lateinit var sizeLabel: TextView
    private lateinit var log: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        c = if (night) {
            Colors(0xFF0E0E11.toInt(), 0xFF1C1C21.toInt(), 0xFFF5F5F7.toInt(), 0xFF9A9AA5.toInt(), 0xFF7FA2FF.toInt(), 0xFF34C77B.toInt(), 0xFF2A2A31.toInt())
        } else {
            Colors(0xFFF2F2F7.toInt(), Color.WHITE, 0xFF111114.toInt(), 0xFF6B6B76.toInt(), 0xFF2F6BF0.toInt(), 0xFF1F9D55.toInt(), 0xFFF2F2F7.toInt())
        }
        actionBar?.hide()
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (night) 0 else lightBars, lightBars)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        column.addView(text(Settings.APP_NAME, 34f, c.text, bold = true))
        column.addView(text("Talk, and clean text lands where you're typing.", 16f, c.dim).apply { setPadding(0, dp(4), 0, dp(20)) })

        micRow = setupRow("Microphone") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
        bubbleRow = setupRow("Floating bubble") { startActivity(Intent(ACTION_ACCESSIBILITY_SETTINGS)) }
        keyboardRow = setupRow("${Settings.APP_NAME} keyboard") {
            val ime = getSystemService(InputMethodManager::class.java)
            if (ime.enabledInputMethodList.any { it.packageName == packageName }) ime.showInputMethodPicker()
            else startActivity(Intent(ACTION_INPUT_METHOD_SETTINGS))
        }
        column.addView(card("Setup", micRow.view, divider(), bubbleRow.view, divider(), keyboardRow.view))

        column.addView(card("Try it", field("Tap here, then use the bubble", lines = 3)))

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
        column.addView(card(
            "Bubble", chips, previewBox, sizeLabel, slider,
            text("Hold to talk, or tap to start and tap again to finish. Drag it anywhere, even onto the keyboard; it remembers the spot.", 14f, c.dim)
                .apply { setPadding(0, dp(8), 0, 0) },
            reset,
        ))

        keyField = field("", password = true)
        val model = field("").apply { setText(settings.model) }
        val replace = field("super base = Supabase", lines = 3).apply { setText(settings.replaceText) }
        val bias = field("Supabase, Fedora").apply { setText(settings.biasText) }
        column.addView(card(
            "Gemini",
            label("API key"), keyField,
            label("Model"), model,
            label("Dictionary: one per line, spoken = Written"), replace,
            label("Preferred spellings, comma-separated"), bias,
            primaryButton("Save") {
                if (keyField.text.isNotBlank()) settings.apiKey = keyField.text.toString()
                keyField.text.clear()
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
                "Your recorded audio goes to Gemini for transcription; dictations are kept only on this phone.",
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

        // Debug builds only: `adb shell am start -n dev.hkgill.murmur/.MainActivity --es selftest t.wav`
        // runs a WAV from the app's files dir through the full pipeline, without speaking into the phone.
        if (BuildConfig.DEBUG) intent.getStringExtra("selftest")?.let(::selfTest)
    }

    private fun selfTest(name: String) = Thread {
        runCatching { Dictation.run(settings, settings.systemPrompt(), java.io.File(filesDir, name).readBytes()) }
            .onSuccess { android.util.Log.i(Dictation.TAG, "selftest ok: $it") }
            .onFailure { Dictation.logFailure(settings, it) }
        runOnUiThread(::refresh)
    }.start()

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

        keyField.hint = when {
            settings.hasOwnKey -> "Saved. Type to replace"
            settings.apiKey.isNotBlank() -> "Using the key built into this app"
            else -> "Paste your Gemini API key"
        }
        showLog()
    }

    private fun showRow(row: SetupRow, done: Boolean, number: String, subtitle: String) {
        row.icon.text = if (done) "✓" else number
        row.icon.setTextColor(if (done) Color.WHITE else c.accent)
        row.icon.background = rounded(if (done) c.ok else c.field, dp(18).toFloat())
        row.subtitle.text = subtitle
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
