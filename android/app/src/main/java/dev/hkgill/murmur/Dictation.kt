package dev.hkgill.murmur

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
 * - Local: Parakeet on the phone, then the rules. Nothing leaves the phone.
 */
object Dictation {
    const val TAG = "Murmur"

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
        settings.log("Gemini ${res.ms} ms ${why.ifEmpty { "ok" }} (${res.timing})\n  heard: ${res.transcript}\n  typed: $text")
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
        settings.log("Groq $total ms (whisper $sttMs ms, $reason)\n  heard: $raw\n  typed: $text")
        // Only a failed or rejected clean-up counts as a fallback in the status line.
        val shown = if (reason.startsWith("cleaned") || !useLlm) "" else reason.substringBefore(" in ")
        return Outcome(text, shown, total)
    }

    private fun local(context: Context, settings: Settings, wav: ByteArray): Outcome {
        val t0 = System.nanoTime()
        val raw = LocalAsr.transcribe(context, wav)
        val text = Rules(settings.dictionary()).apply(raw)
        val total = ms(t0)
        Log.i(TAG, "local $total ms for ${Recorder.durationMs(wav)} ms of audio")
        settings.log("Local $total ms\n  heard: $raw\n  typed: $text")
        return Outcome(text, "", total)
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
