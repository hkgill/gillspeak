package dev.hkgill.gillspeak

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Groq's OpenAI-compatible API: Whisper large-v3-turbo for speech-to-text (the audio leaves the phone), and a
 * small chat model for the text-only clean-up, which the gate skips for short, clean dictations.
 */
class Groq(private val apiKey: String) {
    /** Returns the transcript. [bias] terms go in Whisper's prompt so it favours those spellings. */
    fun transcribe(wav: ByteArray, bias: List<String>): String {
        val boundary = "gillspeak" + System.nanoTime()
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        field("model", STT_MODEL)
        field("response_format", "json")
        field("temperature", "0")
        if (bias.isNotEmpty()) field("prompt", bias.joinToString(", "))
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        body.write(wav)
        body.write("\r\n--$boundary--\r\n".toByteArray())
        val out = post("audio/transcriptions", "multipart/form-data; boundary=$boundary", body.toByteArray())
        return runCatching { JSONObject(out).getString("text").trim() }.getOrElse { throw GeminiError("error", "unexpected transcription response") }
    }

    /** gillspeak's clean-up: the same system prompt and payload format as the desktop's Gemini cleaner. */
    fun clean(systemPrompt: String, text: String, bias: List<String>): String {
        val payload = "Mode: default (Keep the speaker's tone.)\nStyle instructions: $STYLE\n" +
            "Preferred spellings: ${bias.joinToString(", ")}\n\n<transcript>\n$text\n</transcript>"
        val body = JSONObject()
            .put("model", CHAT_MODEL)
            .put("temperature", 0)
            .put("reasoning_effort", "low")
            .put("max_completion_tokens", 1024)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt))
                .put(JSONObject().put("role", "user").put("content", payload)))
        return parseChat(post("chat/completions", "application/json", body.toString().toByteArray()), text)
    }

    private fun post(path: String, contentType: String, body: ByteArray): String {
        val conn = URL("$BASE/$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 20_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code != 200) {
                val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val msg = runCatching { JSONObject(detail).getJSONObject("error").getString("message") }.getOrDefault("HTTP $code")
                throw GeminiError(if (code == 429) "rate_limit" else "http_$code", msg.take(200))
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            conn.disconnect()
            throw GeminiError("network", e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        const val BASE = "https://api.groq.com/openai/v1"
        const val STT_MODEL = "whisper-large-v3-turbo"
        const val CHAT_MODEL = "openai/gpt-oss-20b"
        /** The desktop's default [llm] instructions. */
        const val STYLE = "Australian English spelling. Never use em dashes."

        private val TAG_RE = Regex("</?transcript>", RegexOption.IGNORE_CASE)

        /**
         * The cleaned text from a chat completion. Anything but a normal stop (e.g. "length": the token limit cut
         * it off) is an error, so the caller keeps the rules text; a truncated reply can still pass the validator.
         */
        fun parseChat(json: String, original: String): String {
            val choice = runCatching { JSONObject(json).getJSONArray("choices").getJSONObject(0) }
                .getOrElse { throw GeminiError("error", "unexpected chat response") }
            val finish = choice.optString("finish_reason")
            if (finish != "stop") throw GeminiError("truncated", "clean-up ended with finish_reason=$finish")
            val content = choice.optJSONObject("message")?.optString("content").orEmpty()
            return stripEcho(content, original)
        }

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
    }
}
