package dev.hkgill.murmur

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup (permission, enable, switch), settings, a test field and the recent-dictation log. */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var micButton: Button
    private lateinit var enableButton: Button
    private lateinit var keyField: EditText
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val pad = dp(16)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(heading("Murmur voice keyboard", 24f))
        column.addView(text("Hold the mic to talk and release to insert, or tap it to latch and tap again to finish."))

        column.addView(heading("Setup"))
        micButton = button("1. Allow the microphone") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
        enableButton = button("2. Turn on Murmur in keyboard settings") { startActivity(Intent(ACTION_INPUT_METHOD_SETTINGS)) }
        column.addView(micButton)
        column.addView(enableButton)
        column.addView(button("3. Switch to Murmur") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() })
        column.addView(EditText(this).apply {
            hint = "Try it here"
            minLines = 3
        })

        column.addView(heading("Gemini"))
        keyField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        column.addView(label("API key"))
        column.addView(keyField)
        val model = EditText(this).apply { setText(settings.model); isSingleLine = true }
        column.addView(label("Model"))
        column.addView(model)

        column.addView(heading("Dictionary"))
        val replace = EditText(this).apply { setText(settings.replaceText); minLines = 3 }
        column.addView(label("One per line: spoken = Written"))
        column.addView(replace)
        val bias = EditText(this).apply { setText(settings.biasText) }
        column.addView(label("Preferred spellings, comma-separated"))
        column.addView(bias)
        column.addView(button("Save") {
            if (keyField.text.isNotBlank()) settings.apiKey = keyField.text.toString()
            settings.model = model.text.toString()
            settings.replaceText = replace.text.toString()
            settings.biasText = bias.text.toString()
            refresh()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        })

        column.addView(heading("Recent dictations"))
        log = text("").apply { setTextIsSelectable(true) }
        column.addView(log)

        val scroll = ScrollView(this).apply {
            addView(column)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)

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
        micButton.text = if (mic) "1. Microphone allowed ✓" else "1. Allow the microphone"
        val enabled = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }
        enableButton.text = if (enabled) "2. Murmur keyboard is on ✓" else "2. Turn on Murmur in keyboard settings"
        keyField.hint = when {
            settings.hasOwnKey -> "Saved (type to replace)"
            settings.apiKey.isNotBlank() -> "Using the key built into this app"
            else -> "Paste your Gemini API key"
        }
        log.text = settings.recentLog().joinToString("\n\n").ifEmpty { "Nothing yet." }
    }

    private fun heading(s: String, size: Float = 18f) = TextView(this).apply {
        text = s
        textSize = size
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun label(s: String) = TextView(this).apply {
        text = s
        textSize = 13f
        alpha = 0.7f
        setPadding(0, dp(8), 0, 0)
    }

    private fun text(s: String) = TextView(this).apply {
        text = s
        textSize = 15f
    }

    private fun button(s: String, onClick: (View) -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener(onClick)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
