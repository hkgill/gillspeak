package dev.hkgill.gillspeak

import android.content.Context
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** User settings and a short on-device log of recent dictations (SharedPreferences, never backed up). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("gillspeak", Context.MODE_PRIVATE)
    private val assets = context.assets
    private val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Cloud engine keys exist only if the user types their own in the app; none is ever built into the APK. */
    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var groqKey: String
        get() = prefs.getString("groq_key", "").orEmpty()
        set(v) = prefs.edit().putString("groq_key", v.trim()).apply()

    /** Which speech engine this phone uses: [ENGINE_GEMINI], [ENGINE_GROQ] or [ENGINE_LOCAL]. */
    var engine: String
        get() = prefs.getString("engine", ENGINE_LOCAL) ?: ENGINE_LOCAL // local only unless the user opts in
        set(v) = prefs.edit().putString("engine", v).apply()

    /** Use Gemma ([LocalLlm]) on this phone when its model is installed: commands the rules miss, and polish. */
    var localAi: Boolean
        get() = prefs.getBoolean("local_ai", true)
        set(v) = prefs.edit().putBoolean("local_ai", v).apply()

    /**
     * Polish Local dictation with Gemma, like the cloud engines' clean-up but on the phone. Off by default: it adds
     * about a second per 25 words, and only runs when [Gate] says the dictation is worth it.
     */
    var localPolish: Boolean
        get() = prefs.getBoolean("local_polish", false)
        set(v) = prefs.edit().putBoolean("local_polish", v).apply()

    /** Debug builds: log rules versus Gemma for every voice command (files/compare.log). Set over adb only. */
    var compareLog: Boolean
        get() = prefs.getBoolean("compare_log", false)
        set(v) = prefs.edit().putBoolean("compare_log", v).apply()

    /**
     * Debug builds: when Parakeet hears nothing in a recording, keep that recording as files/empty.wav (the last one
     * only) so it can be checked over adb. Off by default; set over adb only. Release builds never write audio.
     */
    var keepEmptyAudio: Boolean
        get() = debuggable && prefs.getBoolean("keep_empty_audio", false)
        set(v) = prefs.edit().putBoolean("keep_empty_audio", v).apply()

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

    // Snooze. Not "bubble_" keys: changing them shouldn't re-lay-out a bubble that's showing.

    /** How long dropping the bubble on the snooze target hides it, in minutes. */
    var snoozeMinutes: Int
        get() = Snooze.minutesOrDefault(prefs.getInt("snooze_minutes", Snooze.DEFAULT_MINUTES))
        set(v) = prefs.edit().putInt("snooze_minutes", Snooze.minutesOrDefault(v)).apply()

    /** When the current snooze ends, in wall-clock milliseconds; 0 when not snoozed. */
    val snoozeUntil get() = prefs.getLong("snooze_until", 0L)

    /** Night snooze: hide the bubble every night between [nightStart] and [nightEnd], minutes after midnight. */
    var nightSnooze: Boolean
        get() = prefs.getBoolean("night_snooze", false)
        set(v) = prefs.edit().putBoolean("night_snooze", v).apply()

    var nightStart: Int
        get() = prefs.getInt("night_start", Snooze.NIGHT_START)
        set(v) = prefs.edit().putInt("night_start", v.coerceIn(0, 24 * 60 - 1)).apply()

    var nightEnd: Int
        get() = prefs.getInt("night_end", Snooze.NIGHT_END)
        set(v) = prefs.edit().putInt("night_end", v.coerceIn(0, 24 * 60 - 1)).apply()

    /** When tonight's night snooze ends, or null outside one or after "End snooze now" skipped it. */
    fun nightUntil(now: Long): Long? {
        if (!nightSnooze) return null
        val end = Snooze.nightEnd(local(now), nightStart, nightEnd)?.let(::millis) ?: return null
        return end.takeIf { it > prefs.getLong("night_skip_until", 0L) }
    }

    /** When the bubble comes back from a snooze of either kind, or null if it isn't snoozed. */
    fun snoozedUntil(now: Long = System.currentTimeMillis()): Long? =
        listOfNotNull(snoozeUntil.takeIf { Snooze.isActive(now, it) }, nightUntil(now)).maxOrNull()

    fun snoozed(now: Long = System.currentTimeMillis()) = snoozedUntil(now) != null

    /** The next moment the bubble may need to appear or disappear by itself, or null if none is due. */
    fun nextSnoozeChange(now: Long = System.currentTimeMillis()): Long? = listOfNotNull(
        snoozeUntil.takeIf { Snooze.isActive(now, it) },
        if (nightSnooze) Snooze.nextNightChange(local(now), nightStart, nightEnd)?.let(::millis) else null,
    ).minOrNull()

    private fun local(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
    private fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Starts a snooze of [snoozeMinutes] and returns when it ends. */
    fun snooze(now: Long = System.currentTimeMillis()): Long =
        Snooze.until(now, snoozeMinutes).also { prefs.edit().putLong("snooze_until", it).apply() }

    /** Ends a snooze now. During the night it shows the bubble until the night ends; the next night hides it again. */
    fun endSnooze(now: Long = System.currentTimeMillis()) {
        val night = nightUntil(now)
        prefs.edit().remove("snooze_until").apply { if (night != null) putLong("night_skip_until", night) }.apply()
    }

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

    fun clearLog() = prefs.edit().remove("log").apply()

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
