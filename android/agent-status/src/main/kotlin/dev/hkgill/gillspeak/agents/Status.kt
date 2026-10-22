package dev.hkgill.gillspeak.agents

import org.json.JSONObject

/** One coding-agent session, as `gillspeak agents serve` reports it (schema v1, see docs/agent-watch-face.md). */
data class Session(
    val agent: String,
    val project: String,
    val state: String,
    val task: String,
    val activity: String,
    val detail: String,
    val done: Int,
    val total: Int,
) {
    val waiting get() = state == WAITING
    val running get() = state == RUNNING
    val finished get() = state == DONE

    /** What it's doing, in a few words: the plan's current step if it has one. */
    val doing get() = task.ifEmpty { activity }

    companion object {
        const val RUNNING = "running"
        const val WAITING = "waiting"
        const val DONE = "done"
    }
}

/** Everything the watch knows at one moment. Sessions arrive sorted: waiting, then running, then finished. */
data class Snapshot(val sessions: List<Session>, val running: Int, val waiting: Int, val doneToday: Int) {
    fun of(agent: Agent) = sessions.filter { it.agent == agent.key }

    companion object {
        const val VERSION = 1
        private const val TEXT_LIMIT = 80
        private val ORDER = listOf(Session.WAITING, Session.RUNNING, Session.DONE)

        /** Throws [IllegalArgumentException] for anything that isn't a v1 status document. */
        fun parse(json: String): Snapshot {
            val root = try {
                JSONObject(json)
            } catch (e: RuntimeException) { // org.json's JSONException
                throw IllegalArgumentException("not JSON", e)
            }
            val version = root.optInt("v", -1)
            require(version == VERSION) { "unsupported status version $version" }
            val list = root.optJSONArray("agents") ?: throw IllegalArgumentException("no agents list")
            val sessions = (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                val total = o.optInt("total", 0).coerceAtLeast(0)
                Session(
                    agent = o.text("agent"),
                    project = o.text("project"),
                    state = o.text("state"),
                    task = o.text("task"),
                    activity = o.text("activity"),
                    detail = o.text("detail"),
                    done = o.optInt("done", 0).coerceIn(0, total),
                    total = total,
                )
            }.sortedBy { ORDER.indexOf(it.state).let { i -> if (i < 0) ORDER.size else i } } // stable: keeps the server's recency
            return Snapshot(
                sessions,
                running = root.optInt("running", sessions.count { it.running }).coerceAtLeast(0),
                waiting = root.optInt("waiting", sessions.count { it.waiting }).coerceAtLeast(0),
                doneToday = root.optInt("doneToday", 0).coerceAtLeast(0),
            )
        }

        private fun JSONObject.text(key: String) = optString(key, "").take(TEXT_LIMIT)
    }
}

/** Where the watch fetches the status, from what was typed on the watch (address:port is enough). */
object Endpoint {
    const val PATH = "/v1/agents"

    /** Null if it isn't an address, or if it's plain http to a host outside your own network: the token would cross the internet unencrypted. */
    fun url(typed: String): String? {
        val t = typed.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val full = if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
        val host = full.substringAfter("://").substringBefore('/').substringBefore(':')
        if (host.isEmpty() || (full.startsWith("http://") && !isLocal(host))) return null
        return full.trimEnd('/').removeSuffix(PATH) + PATH
    }

    /** Loopback, private, link-local or Tailscale (100.64/10) addresses, `.local` names and single-label names. */
    fun isLocal(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".local") || '.' !in h) return true
        val p = h.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || p[0] == 127 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) ||
            (p[0] == 169 && p[1] == 254) || (p[0] == 100 && p[1] in 64..127)
    }

    /** The token as `gillspeak agents token` prints it ("abcd-efgh-jkmn"), however it was typed. */
    fun token(typed: String) = typed.lowercase().filter { it.isLetterOrDigit() }
}
