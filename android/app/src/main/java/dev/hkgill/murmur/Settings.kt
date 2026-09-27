package dev.hkgill.murmur

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** User settings and a short on-device log of recent dictations (SharedPreferences, never backed up). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("murmur", Context.MODE_PRIVATE)
    private val assets = context.assets

    /** The key typed in the app wins; otherwise the one baked in from local.properties at build time. */
    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty().ifBlank { BuildConfig.GEMINI_API_KEY }
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    val hasOwnKey get() = !prefs.getString("api_key", "").isNullOrBlank()

    var model: String
        get() = prefs.getString("model", "").orEmpty().ifBlank { DEFAULT_MODEL }
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    var replaceText: String
        get() = prefs.getString("replace", null) ?: DEFAULT_REPLACE
        set(v) = prefs.edit().putString("replace", v).apply()

    var biasText: String
        get() = prefs.getString("bias", null) ?: DEFAULT_BIAS
        set(v) = prefs.edit().putString("bias", v).apply()

    fun dictionary() = Dictionary.parse(replaceText, biasText)

    fun systemPrompt(): String =
        listOf("audio_preface.txt", "clean_v2.txt").joinToString("") { name -> assets.open(name).bufferedReader().use { it.readText() } }

    fun log(line: String) {
        val stamp = SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault()).format(Date())
        val entries = (listOf("$stamp  $line") + recentLog()).take(MAX_LOG)
        prefs.edit().putString("log", entries.joinToString(SEP)).apply()
    }

    fun recentLog(): List<String> = prefs.getString("log", "").orEmpty().split(SEP).filter { it.isNotBlank() }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val DEFAULT_REPLACE = "super base = Supabase\nget hub = GitHub\ncube control = kubectl"
        const val DEFAULT_BIAS = "Supabase, Fedora, Parakeet"
        private const val MAX_LOG = 30
        private const val SEP = "\u001e"
    }
}
