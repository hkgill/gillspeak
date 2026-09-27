package dev.hkgill.gillspeak

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** User settings and a short on-device log of recent dictations (SharedPreferences, never backed up). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("gillspeak", Context.MODE_PRIVATE)
    private val assets = context.assets

    /** The key typed in the app wins; otherwise the one baked in from local.properties at build time. */
    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty().ifBlank { BuildConfig.GEMINI_API_KEY }
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    val hasOwnKey get() = !prefs.getString("api_key", "").isNullOrBlank()

    var groqKey: String
        get() = prefs.getString("groq_key", "").orEmpty().ifBlank { BuildConfig.GROQ_API_KEY }
        set(v) = prefs.edit().putString("groq_key", v.trim()).apply()

    val hasOwnGroqKey get() = !prefs.getString("groq_key", "").isNullOrBlank()

    /** Which speech engine this phone uses: [ENGINE_GEMINI], [ENGINE_GROQ] or [ENGINE_LOCAL]. */
    var engine: String
        get() = prefs.getString("engine", ENGINE_GEMINI) ?: ENGINE_GEMINI
        set(v) = prefs.edit().putString("engine", v).apply()

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

    // Floating bubble. Position is relative to the keyboard, so it follows the keyboard when its height changes.

    /** Circle diameter, or bar height, in dp. */
    var bubbleSize: Int
        get() = prefs.getInt("bubble_size", 56)
        set(v) = prefs.edit().putInt("bubble_size", v.coerceIn(BUBBLE_MIN, BUBBLE_MAX)).apply()

    /** "circle", or "bar": a full-width strip laid over the keyboard. */
    var bubbleShape: String
        get() = prefs.getString("bubble_shape", "circle") ?: "circle"
        set(v) = prefs.edit().putString("bubble_shape", v).apply()

    /** Horizontal centre of the circle as a fraction of the screen width. */
    var bubbleX: Float
        get() = prefs.getFloat("bubble_x", 0.9f)
        set(v) = prefs.edit().putFloat("bubble_x", v.coerceIn(0f, 1f)).apply()

    /**
     * Top of the bubble relative to the top of the keyboard, in dp: negative is above it, positive over it.
     * Until the user drags it, it sits just above the keyboard (the bar sits over the keyboard's top edge).
     */
    var bubbleDy: Int
        get() = prefs.getInt("bubble_dy", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            ?: if (bubbleShape == "bar") 0 else -(bubbleSize + 12)
        set(v) = prefs.edit().putInt("bubble_dy", v).apply()

    /** Saves both coordinates in one write, so listeners never see half an update. */
    fun setBubblePosition(x: Float, dy: Int) =
        prefs.edit().putFloat("bubble_x", x.coerceIn(0f, 1f)).putInt("bubble_dy", dy).apply()

    fun resetBubblePosition() = prefs.edit().remove("bubble_x").remove("bubble_dy").apply()

    /** Calls [listener] when a bubble setting changes. Keep the returned object: preferences hold listeners weakly. */
    fun onBubbleChange(listener: () -> Unit) =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key?.startsWith("bubble_") == true) listener() }
            .also(prefs::registerOnSharedPreferenceChangeListener)

    /** The desktop clean-up prompt alone, for text-only clean-up (Groq). */
    fun cleanPrompt(): String = assets.open("clean_v2.txt").bufferedReader().use { it.readText() }

    /** Can this phone dictate with its chosen engine? Returns null when ready, otherwise what's missing. */
    fun engineProblem(context: Context): String? = when (engine) {
        ENGINE_GROQ -> if (groqKey.isBlank()) "Tap here to add a Groq API key" else null
        ENGINE_LOCAL -> if (!LocalAsr.isReady(context)) "Tap here to download the on-device model" else null
        else -> if (apiKey.isBlank()) "Tap here to add a Gemini API key" else null
    }

    /** The Gemini prompt: audio instructions + the desktop clean-up prompt. */
    fun systemPrompt(): String =
        listOf("audio_preface.txt", "clean_v2.txt").joinToString("") { name -> assets.open(name).bufferedReader().use { it.readText() } }

    fun log(line: String) {
        val stamp = SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault()).format(Date())
        val entries = (listOf("$stamp  $line") + recentLog()).take(MAX_LOG)
        prefs.edit().putString("log", entries.joinToString(SEP)).apply()
    }

    fun recentLog(): List<String> = prefs.getString("log", "").orEmpty().split(SEP).filter { it.isNotBlank() }

    companion object {
        /** The name people see. The package id stays dev.hkgill.gillspeak so installs upgrade in place. */
        const val APP_NAME = "gillspeak"
        const val ENGINE_GEMINI = "gemini"
        const val ENGINE_GROQ = "groq"
        const val ENGINE_LOCAL = "local"
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val DEFAULT_REPLACE = "super base = Supabase\nget hub = GitHub\ncube control = kubectl"
        const val DEFAULT_BIAS = "Supabase, Fedora, Parakeet"
        const val BUBBLE_MIN = 40
        const val BUBBLE_MAX = 200
        private const val MAX_LOG = 30
        private const val SEP = "\u001e"
    }
}

/** A debug self-test file name: a plain name ending in .wav, so it can't reach outside the app's files dir. */
fun isSafeTestFile(name: String) = Regex("""[A-Za-z0-9_-]{1,64}\.wav""").matches(name)
