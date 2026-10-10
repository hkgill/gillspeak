package dev.hkgill.gillspeak

import android.content.Context
import android.util.Log

/**
 * The pipeline after recording, for the engine this phone uses. Runs on a worker thread; the keyboard, the bubble
 * and the debug self-test share it.
 *
 * - Gemini: one request with the audio returns a verbatim transcript and a cleaned version; the cleaned text is
 *   used when the validator accepts it, otherwise the rules-cleaned transcript. Audio goes to Google.
 * - Groq: Whisper transcribes (audio goes to Groq), then the desktop pipeline: rules, the gate, a text-only
 *   clean-up when it's worth it, the validator. Any clean-up failure falls back to the rules text.
 * - Local: Parakeet on the phone, then the rules, then (if turned on) Gemma's polish behind the same gate and
 *   validator as Groq's. Nothing leaves the phone.
 */
object Dictation {
    const val TAG = "gillspeak"

    data class Outcome(val text: String, val reason: String, val ms: Long)

    fun run(context: Context, settings: Settings, wav: ByteArray, engine: String = settings.engine): Outcome = when (engine) {
        Settings.ENGINE_GROQ -> groq(settings, wav)
        Settings.ENGINE_LOCAL -> local(context, settings, wav)
        else -> gemini(settings, wav)
    }

    private fun gemini(settings: Settings, wav: ByteArray): Outcome {
        val dictionary = settings.dictionary()
        val res = Gemini(settings.apiKey, settings.model, settings.systemPrompt()).transcribe(wav, dictionary.bias)
        val fallback = Rules(dictionary).apply(res.transcript)
        val why = when {
            res.transcript.isBlank() && res.text.isBlank() -> ""
            res.text.isBlank() -> "empty"
            else -> Validate.check(fallback, res.text)
        }
        val text = if (why.isEmpty()) dictionary.apply(res.text) else fallback
        Log.i(TAG, "gemini ${res.ms} ms: ${res.timing}")
        if (res.transcript.isNotBlank() || res.text.isNotBlank()) settings.log("Gemini ${res.ms} ms ${why.ifEmpty { "ok" }} (${res.timing})\n  heard: ${res.transcript}\n  typed: $text")
        return Outcome(text, why, res.ms)
    }

    private fun groq(settings: Settings, wav: ByteArray): Outcome {
        val t0 = System.nanoTime()
        val dictionary = settings.dictionary()
        val groq = Groq(settings.groqKey)
        val raw = groq.transcribe(wav, dictionary.bias)
        val sttMs = ms(t0)
        val rules = Rules(dictionary).apply(raw)
        val (useLlm, gate) = Gate.decide(rules)
        var text = rules
        var reason = gate
        if (useLlm) {
            val t1 = System.nanoTime()
            reason = try {
                val cleaned = groq.clean(settings.cleanPrompt(), rules, dictionary.bias)
                val why = Validate.check(rules, cleaned)
                if (why.isEmpty()) text = dictionary.apply(cleaned)
                why.ifEmpty { "cleaned" }
            } catch (e: Exception) {
                Log.w(TAG, "groq clean-up failed; using the rules text", e)
                "cleanup_failed:${(e as? GeminiError)?.kind ?: e.javaClass.simpleName}"
            }
            reason += " in ${ms(t1)} ms"
        }
        val total = ms(t0)
        Log.i(TAG, "groq $total ms: whisper $sttMs ms, $reason")
        if (raw.isNotBlank()) settings.log("Groq $total ms (whisper $sttMs ms, $reason)\n  heard: $raw\n  typed: $text")
        // Only a failed or rejected clean-up counts as a fallback in the status line.
        val shown = if (reason.startsWith("cleaned") || !useLlm) "" else reason.substringBefore(" in ")
        return Outcome(text, shown, total)
    }

    private fun local(context: Context, settings: Settings, wav: ByteArray): Outcome {
        val t0 = System.nanoTime()
        val raw = LocalAsr.transcribe(context, wav)
        val sttMs = ms(t0)
        val dictionary = settings.dictionary()
        val rules = Rules(dictionary).apply(raw)
        val (text, reason) = if (settings.localPolish && LocalLlm.isReady(context)) polish(context, settings, rules) else rules to ""
        val total = ms(t0)
        Log.i(TAG, "local $total ms for ${Recorder.durationMs(wav)} ms of audio (parakeet $sttMs ms${if (reason.isEmpty()) "" else ", $reason"})")
        if (raw.isNotBlank()) settings.log("Local $total ms${if (reason.isEmpty()) "" else " ($reason)"}\n  heard: $raw\n  typed: $text")
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
            val reply = LocalLlm.ask(context, settings.cleanPrompt(), Groq.cleanPayload(rules, dictionary.bias), maxTokens = 1024)
            val cleaned = Groq.stripEcho(reply.text, rules)
            val why = Validate.check(rules, cleaned)
            if (why.isEmpty()) text = dictionary.apply(cleaned)
            why.ifEmpty { "polished" }
        } catch (e: Exception) {
            Log.w(TAG, "gemma polish failed; using the rules text", e)
            "polish_failed:${e.javaClass.simpleName}"
        }
        return text to "$reason by Gemma in ${ms(t0)} ms"
    }

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    /** Records a failure with its exception class, so "null" messages still say what went wrong. */
    fun logFailure(settings: Settings, e: Throwable): String {
        Log.e(TAG, "dictation failed", e)
        val kind = (e as? GeminiError)?.kind ?: e.javaClass.simpleName
        val cause = generateSequence(e) { it.cause }.last()
        settings.log("FAILED $kind: ${e.message ?: cause.message ?: cause.javaClass.simpleName}")
        return kind
    }
}
