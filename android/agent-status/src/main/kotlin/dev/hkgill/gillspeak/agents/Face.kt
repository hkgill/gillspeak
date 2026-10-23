package dev.hkgill.gillspeak.agents

enum class Agent(val key: String, val label: String, val fullName: String) {
    CLAUDE("claude", "Claude", "Claude Code"),
    CODEX("codex", "Codex", "Codex"),
}

/** What one complication shows: [value] runs from 0 to [max]; [description] is read by TalkBack. */
data class Reading(val value: Float, val max: Float, val text: String, val title: String, val description: String)

/** The watch face's complications, worked out from a [Snapshot]. A null snapshot means there's no fresh status. */
object Face {
    /** The arc and the chip for one agent: its most pressing session (waiting, else running, else finished). */
    fun agent(snapshot: Snapshot?, agent: Agent, offline: String): Reading {
        if (snapshot == null) return Reading(0f, 1f, offline, agent.label, "${agent.fullName}: no status ($offline)")
        val sessions = snapshot.of(agent)
        val s = sessions.firstOrNull()
            ?: return Reading(0f, 1f, "–", agent.label, "${agent.fullName}: no session")
        val title = if (sessions.size > 1) "${agent.label}+${sessions.size - 1}" else agent.label
        val where = if (s.project.isEmpty()) agent.fullName else "${agent.fullName}, ${s.project}"
        val steps = if (s.total > 0) ", step ${minOf(s.done + 1, s.total)} of ${s.total}" else ""
        val description = when {
            s.waiting -> "$where needs you" + (if (s.detail.isEmpty()) "" else ": ${s.detail}")
            s.finished -> "$where finished"
            else -> "$where: ${s.doing.ifEmpty { "working" }}$steps"
        }
        return when {
            s.total > 0 -> Reading(s.done.toFloat(), s.total.toFloat(), "${s.done}/${s.total}", title, description)
            s.finished -> Reading(1f, 1f, "Done", title, description)
            s.waiting -> Reading(0f, 1f, "Wait", title, description)
            else -> Reading(0f, 1f, "Busy", title, description)
        }
    }

    /** The "needs you" slot: [Reading.value] is how many sessions wait for an answer; the face turns amber above 0. */
    fun attention(snapshot: Snapshot?, offline: String): Reading {
        if (snapshot == null) return Reading(0f, 1f, offline, "", "Agent status: $offline")
        val max = maxOf(1, snapshot.sessions.size, snapshot.waiting).toFloat()
        val first = snapshot.sessions.firstOrNull { it.waiting }
        if (snapshot.waiting > 0 && first != null) {
            val who = Agent.entries.firstOrNull { it.key == first.agent }?.fullName ?: first.agent
            val what = if (first.detail.isEmpty()) "" else ": ${first.detail}"
            return Reading(snapshot.waiting.toFloat(), max, "Needs you", who, "$who needs you$what")
        }
        val text = when {
            snapshot.running == 1 -> "1 agent running"
            snapshot.running > 1 -> "${snapshot.running} agents running"
            snapshot.sessions.isNotEmpty() -> "All done"
            else -> "No agents"
        }
        return Reading(0f, max, text, "", text)
    }

    /** The third chip: turns finished today, across every agent. */
    fun doneToday(snapshot: Snapshot?, offline: String): Reading {
        if (snapshot == null) return Reading(0f, 1f, offline, "done", "Finished today: $offline")
        val n = snapshot.doneToday
        return Reading(n.toFloat(), maxOf(1, n).toFloat(), "$n", "done", "$n agent ${if (n == 1) "task" else "tasks"} finished today")
    }
}
