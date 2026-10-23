package dev.hkgill.gillspeak.watch

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.RemoteException
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService.ComplicationRequestListener
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import dev.hkgill.gillspeak.agents.Agent
import dev.hkgill.gillspeak.agents.Face
import dev.hkgill.gillspeak.agents.Reading
import dev.hkgill.gillspeak.agents.Session
import dev.hkgill.gillspeak.agents.Snapshot

/**
 * One of the face's complications. Each works out its [Reading] from the shared status; a tap opens the agent list.
 * Any watch face can use them, not only gillspeak's.
 */
abstract class AgentComplicationService : ComplicationDataSourceService() {
    protected abstract fun reading(snapshot: Snapshot?, offline: String): Reading

    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        val app = applicationContext
        StatusRepository.executor.execute {
            val (snapshot, offline) = StatusRepository.current(app).forFace()
            try {
                listener.onComplicationData(build(app, request.complicationType, reading(snapshot, offline)))
            } catch (e: RemoteException) {
                // The face went away while we were fetching.
            }
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(this, type, reading(PREVIEW, ""))

    private fun build(c: Context, type: ComplicationType, r: Reading): ComplicationData? {
        val tap = PendingIntent.getActivity(c, 0, Intent(c, AgentsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val description = text(r.description)
        val title = r.title.takeIf { it.isNotEmpty() }?.let { text(it) }
        return when (type) {
            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(r.value.coerceIn(0f, r.max), 0f, r.max, description)
                    .setText(text(r.text)).setTitle(title).setTapAction(tap).build()
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text(r.text), description).setTitle(title).setTapAction(tap).build()
            else -> null
        }
    }

    private fun text(s: String) = PlainComplicationText.Builder(s).build()

    companion object {
        /** What the face editor shows before there's real data. */
        private val PREVIEW = Snapshot(
            listOf(
                Session("claude", "project", Session.RUNNING, "Running tests", "", "", 4, 7),
                Session("codex", "project", Session.RUNNING, "Editing", "", "", 2, 5),
            ),
            running = 2, waiting = 0, doneToday = 3,
        )
    }
}

class ClaudeComplicationService : AgentComplicationService() {
    override fun reading(snapshot: Snapshot?, offline: String) = Face.agent(snapshot, Agent.CLAUDE, offline)
}

class CodexComplicationService : AgentComplicationService() {
    override fun reading(snapshot: Snapshot?, offline: String) = Face.agent(snapshot, Agent.CODEX, offline)
}

class AttentionComplicationService : AgentComplicationService() {
    override fun reading(snapshot: Snapshot?, offline: String) = Face.attention(snapshot, offline)
}

class DoneTodayComplicationService : AgentComplicationService() {
    override fun reading(snapshot: Snapshot?, offline: String) = Face.doneToday(snapshot, offline)
}
