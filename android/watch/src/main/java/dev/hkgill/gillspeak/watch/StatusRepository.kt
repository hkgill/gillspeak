package dev.hkgill.gillspeak.watch

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import dev.hkgill.gillspeak.agents.Endpoint
import dev.hkgill.gillspeak.agents.Snapshot
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The latest agent status from the computer. Every complication asks for it, so one fetch serves them all: it's
 * fetched at most every [FRESH_MS], and kept so the face has something to show between fetches.
 */
object StatusRepository {
    private const val PREFS = "agents"
    private const val FRESH_MS = 30_000L
    private const val STALE_MS = 15 * 60_000L // older than this, the face says "Off" rather than show old news
    private const val TIMEOUT_MS = 8_000
    private const val MAX_BYTES = 256 * 1024

    /** Fetches run here, one at a time, never on the main thread. */
    val executor: ExecutorService = Executors.newSingleThreadExecutor()

    class Status(val snapshot: Snapshot?, val fetchedAt: Long, val error: String?, val configured: Boolean) {
        /** The snapshot if it's recent enough to put on the face; otherwise null and the word the face shows instead. */
        fun forFace(now: Long = System.currentTimeMillis()): Pair<Snapshot?, String> = when {
            !configured -> null to "Set up"
            snapshot != null && now - fetchedAt < STALE_MS -> snapshot to ""
            else -> null to "Off"
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun address(c: Context) = prefs(c).getString("address", "").orEmpty()
    fun token(c: Context) = prefs(c).getString("token", "").orEmpty()

    /** Call on [executor], like [current]: a fetch still running for the old address could otherwise land after it. */
    fun save(c: Context, address: String, token: String) {
        prefs(c).edit().clear().putString("address", address.trim()).putString("token", Endpoint.token(token)).commit()
    }

    fun cached(c: Context): Status {
        val p = prefs(c)
        val snapshot = p.getString("json", null)?.let { runCatching { Snapshot.parse(it) }.getOrNull() }
        val configured = Endpoint.url(address(c)) != null && token(c).isNotEmpty()
        return Status(snapshot, p.getLong("fetchedAt", 0), p.getString("error", null), configured)
    }

    /** The status, fetched first if the last try is older than [maxAgeMs]. Blocks: call it on [executor]. */
    @Synchronized
    fun current(c: Context, maxAgeMs: Long = FRESH_MS): Status {
        val p = prefs(c)
        val before = cached(c)
        val now = System.currentTimeMillis()
        val endpoint = Endpoint.url(address(c))
        if (!before.configured || endpoint == null || now - p.getLong("triedAt", 0) < maxAgeMs) return before
        val edit = p.edit().putLong("triedAt", now)
        val changed = try {
            val json = fetch(endpoint, token(c))
            edit.putString("json", json).putLong("fetchedAt", now).remove("error")
            json != p.getString("json", null)
        } catch (e: IOException) {
            edit.putString("error", describe(e))
            false
        } catch (e: IllegalArgumentException) {
            edit.putString("error", "That isn't gillspeak's status server")
            false
        }
        edit.commit()
        if (changed) Complications.requestUpdates(c)
        return cached(c)
    }

    private fun fetch(endpoint: String, token: String): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            when (val code = conn.responseCode) {
                200 -> Unit
                401 -> throw IOException("Wrong token")
                else -> throw IOException("The computer answered $code")
            }
            val body = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw IOException("The answer is too big")
                }
                out.toString("UTF-8")
            }
            Snapshot.parse(body) // only keep what parses
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(e: IOException) = when (e) {
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> "Can't reach the computer"
        is SocketTimeoutException -> "The computer didn't answer"
        else -> e.message ?: "Network error"
    }
}

object Complications {
    private val sources = listOf(
        ClaudeComplicationService::class.java,
        CodexComplicationService::class.java,
        AttentionComplicationService::class.java,
        DoneTodayComplicationService::class.java,
    )

    /** Ask the face to fetch every complication again, after the status changed. */
    fun requestUpdates(c: Context) {
        for (source in sources) {
            ComplicationDataSourceUpdateRequester.create(c, ComponentName(c, source)).requestUpdateAll()
        }
    }
}
