package dev.hkgill.murmur

import android.util.Log

/**
 * The pipeline after recording: Gemini (verbatim transcript + cleaned text) -> rules on the transcript
 * -> validator -> dictionary. Runs on a worker thread; the keyboard and the debug self-test share it.
 */
object Dictation {
    const val TAG = "Murmur"

    data class Outcome(val text: String, val reason: String, val ms: Long)

    fun run(settings: Settings, systemPrompt: String, wav: ByteArray): Outcome {
        val dictionary = settings.dictionary()
        val res = Gemini(settings.apiKey, settings.model, systemPrompt).transcribe(wav, dictionary.bias)
        val fallback = Rules(dictionary).apply(res.transcript)
        val why = when {
            res.transcript.isBlank() && res.text.isBlank() -> ""
            res.text.isBlank() -> "empty"
            else -> Validate.check(fallback, res.text)
        }
        val text = if (why.isEmpty()) dictionary.apply(res.text) else fallback
        Log.i(TAG, "gemini ${res.ms} ms: ${res.timing}")
        settings.log("${res.ms} ms ${why.ifEmpty { "ok" }} (${res.timing})\n  heard: ${res.transcript}\n  typed: $text")
        return Outcome(text, why, res.ms)
    }

    /** Records a failure with its exception class, so "null" messages still say what went wrong. */
    fun logFailure(settings: Settings, e: Throwable): String {
        Log.e(TAG, "dictation failed", e)
        val kind = (e as? GeminiError)?.kind ?: e.javaClass.simpleName
        val cause = generateSequence(e) { it.cause }.last()
        settings.log("FAILED $kind: ${e.message ?: cause.message ?: cause.javaClass.simpleName}")
        return kind
    }
}
