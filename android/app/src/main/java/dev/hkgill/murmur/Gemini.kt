package dev.hkgill.murmur

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeminiError(val kind: String, message: String) : Exception(message)

/**
 * One request does both jobs: Gemini transcribes the audio verbatim ("transcript") and cleans it with
 * Murmur's clean_v2 prompt ("text"). The verbatim transcript gives the rules fallback something to work with.
 */
class Gemini(private val apiKey: String, private val model: String, private val systemPrompt: String) {
    /** [timing] says where the time went: upload, waiting for Gemini, and any thinking tokens. */
    data class Result(val transcript: String, val text: String, val ms: Long, val timing: String)

    private var sendMs = 0L
    private var waitMs = 0L

    fun transcribe(wav: ByteArray, bias: List<String>, mode: String = "default"): Result {
        val t0 = System.nanoTime()
        val parts = JSONArray()
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", Base64.encodeToString(wav, Base64.NO_WRAP))))
            .put(JSONObject().put("text", "Mode: $mode\nPreferred spellings: ${bias.joinToString(", ")}"))
        val schema = JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject().put("transcript", JSONObject().put("type", "STRING")).put("text", JSONObject().put("type", "STRING")))
            .put("required", JSONArray().put("transcript").put("text"))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0).put("responseMimeType", "application/json").put("responseSchema", schema))

        val data = request("models/$model:generateContent", body.toString())
        val text = runCatching {
            val content = JSONObject(data).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            (0 until content.length()).joinToString("") { content.getJSONObject(it).optString("text") }
        }.getOrElse { throw GeminiError("error", "unexpected response shape") }
        val out = runCatching { JSONObject(text) }.getOrElse { throw GeminiError("error", "response is not JSON") }
        val thoughts = runCatching { JSONObject(data).getJSONObject("usageMetadata").optInt("thoughtsTokenCount") }.getOrDefault(0)
        val timing = "upload $sendMs ms, wait $waitMs ms" + if (thoughts > 0) ", thinking $thoughts tokens" else ""
        return Result(out.optString("transcript").trim(), out.optString("text").trim(), (System.nanoTime() - t0) / 1_000_000, timing)
    }

    /** Opens the TLS connection ahead of time with a free metadata call, so dictation doesn't pay for it. */
    fun warm() {
        runCatching { request("models/$model", null) }
    }

    private fun request(path: String, body: String?): String {
        // No disconnect() on success: a fully read stream returns the socket to the keep-alive pool.
        val conn = URL("$BASE/$path").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 30_000
            val t0 = System.nanoTime()
            conn.setRequestProperty("x-goog-api-key", apiKey)
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val t1 = System.nanoTime()
            val code = conn.responseCode
            sendMs = (t1 - t0) / 1_000_000
            waitMs = (System.nanoTime() - t1) / 1_000_000
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
        const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    }
}
