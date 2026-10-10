package dev.hkgill.gillspeak

import android.content.Context
import android.util.Log

/**
 * The pipeline after recording: Parakeet on the phone, then the rules, then (if turned on) Gemma's polish behind the
 * desktop's gate and validator. Nothing leaves the phone. Runs on a worker thread; the keyboard, the bubble and the
 * debug self-test share it.
 */
object Dictation {
    const val TAG = "gillspeak"

    /** The desktop's default [llm] instructions. */
    private const val STYLE = "Australian English spelling. Never use em dashes."
    private val TAG_RE = Regex("</?transcript>", RegexOption.IGNORE_CASE)

    data class Outcome(val text: String, val reason: String, val ms: Long)

    fun run(context: Context, settings: Settings, wav: ByteArray): Outcome {
        val t0 = System.nanoTime()
        val raw = LocalAsr.transcribe(context, wav)
        val sttMs = ms(t0)
        val dictionary = settings.dictionary()
        val rules = Rules(dictionary).apply(raw)
        val (text, reason) = if (settings.localPolish && LocalLlm.isReady(context)) polish(context, settings, rules) else rules to ""
        val total = ms(t0)
        Log.i(TAG, "local $total ms for ${Recorder.durationMs(wav)} ms of audio (parakeet $sttMs ms${if (reason.isEmpty()) "" else ", $reason"})")
        if (raw.isNotBlank()) settings.log("Local $total ms${if (reason.isEmpty()) "" else " ($reason)"}\n  heard: $raw\n  typed: $text")
        if (raw.isBlank()) {
            Log.w(TAG, "local: Parakeet heard nothing in ${Recorder.durationMs(wav)} ms of audio")
            if (settings.keepEmptyAudio) runCatching { java.io.File(context.filesDir, "empty.wav").writeBytes(wav) }
        }
        // Only a failed or rejected polish counts as a fallback in the status line.
        val shown = if (reason.isEmpty() || reason.startsWith("polished") || reason.startsWith("short") || reason == "empty") "" else reason.substringBefore(" by ")
        return Outcome(text, shown, total)
    }

    /**
     * Gemma's polish of rules-cleaned [rules], when [Gate] says it's worth it and [Validate] accepts the result;
     * otherwise [rules] unchanged. Returns the text and why, for the log. Worker thread only.
     */
    fun polish(context: Context, settings: Settings, rules: String): Pair<String, String> {
        val (useLlm, gate) = Gate.decide(rules)
        if (!useLlm) return rules to gate
        val t0 = System.nanoTime()
        val dictionary = settings.dictionary()
        var text = rules
        val reason = try {
            val reply = LocalLlm.ask(context, settings.cleanPrompt(), cleanPayload(rules, dictionary.bias), maxTokens = 1024)
            val cleaned = stripEcho(reply.text, rules)
            val why = Validate.check(rules, cleaned)
            if (why.isEmpty()) text = dictionary.apply(cleaned)
            why.ifEmpty { "polished" }
        } catch (e: Exception) {
            Log.w(TAG, "gemma polish failed; using the rules text", e)
            "polish_failed:${e.javaClass.simpleName}"
        }
        return text to "$reason by Gemma in ${ms(t0)} ms"
    }

    /** The user message for a text-only clean-up, as the desktop sends it. */
    fun cleanPayload(text: String, bias: List<String>) = "Mode: default (Keep the speaker's tone.)\nStyle instructions: $STYLE\n" +
        "Preferred spellings: ${bias.joinToString(", ")}\n\n<transcript>\n$text\n</transcript>"

    /** Removes wrappers a model sometimes adds: transcript tags and surrounding quotes (as cleaner.py does). */
    fun stripEcho(out: String, original: String): String {
        var s = TAG_RE.replace(out, "").trim()
        for ((open, close) in listOf("\"" to "\"", "“" to "”", "'" to "'", "`" to "`")) {
            if (s.length >= 2 && s.startsWith(open) && s.endsWith(close) && !original.trim().startsWith(open)) {
                s = s.substring(open.length, s.length - close.length).trim()
            }
        }
        return s
    }

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    /** Records a failure with its exception class, so "null" messages still say what went wrong. */
    fun logFailure(settings: Settings, e: Throwable): String {
        Log.e(TAG, "dictation failed", e)
        val kind = e.javaClass.simpleName
        val cause = generateSequence(e) { it.cause }.last()
        settings.log("FAILED $kind: ${e.message ?: cause.message ?: cause.javaClass.simpleName}")
        return kind
    }
}
