package dev.hkgill.gillspeak.watch

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.hkgill.gillspeak.agents.Agent
import dev.hkgill.gillspeak.agents.Endpoint
import dev.hkgill.gillspeak.agents.Session
import java.text.DateFormat
import java.util.Date

/** What a tap on the face opens: every agent session, then where to find the computer. */
class AgentsActivity : Activity() {
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var address: EditText
    private lateinit var token: EditText

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Round screens: keep everything inside the circle.
        val side = (resources.displayMetrics.widthPixels * 0.13f).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, (28 * dp).toInt(), side, (56 * dp).toInt())
        }
        column.addView(label("Agents", 20f, INK, bold = true).apply { gravity = Gravity.CENTER })
        status = label("", 13f, INK2).apply { gravity = Gravity.CENTER }
        column.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list)
        column.addView(button("Refresh") { refresh(0) })

        column.addView(label("Computer", 15f, INK, bold = true).apply { setPadding(0, (20 * dp).toInt(), 0, 0) })
        column.addView(label("Run `gillspeak agents serve` on it, then enter the address it shows.", 12f, INK2))
        address = field("192.168.x.x:8765", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        column.addView(address)
        column.addView(label("Token, from `gillspeak agents token`", 12f, INK2))
        token = field("abcd-efgh-jkmn", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        column.addView(token)
        column.addView(button("Save") { save() })

        scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
        address.setText(StatusRepository.address(this))
        token.setText(StatusRepository.token(this))
    }

    override fun onResume() {
        super.onResume()
        show(StatusRepository.cached(this))
        refresh(5_000)
        scroll.requestFocus() // the rotating bezel scrolls the focused view
    }

    private fun save() {
        val typedAddress = address.text.toString()
        val typedToken = token.text.toString()
        if (Endpoint.url(typedAddress) == null) {
            status.text = "Use the address `gillspeak agents serve` shows (plain http only on your own network)"
            return
        }
        status.text = "Updating…"
        val app = applicationContext
        StatusRepository.executor.execute {
            StatusRepository.save(app, typedAddress, typedToken)
            val s = StatusRepository.current(app, 0)
            runOnUiThread { if (!isDestroyed) show(s) }
        }
    }

    private fun refresh(maxAgeMs: Long) {
        if (!StatusRepository.cached(this).configured) return
        status.text = "Updating…"
        val app = applicationContext
        StatusRepository.executor.execute {
            val s = StatusRepository.current(app, maxAgeMs)
            runOnUiThread { if (!isDestroyed) show(s) }
        }
    }

    private fun show(s: StatusRepository.Status) {
        status.text = when {
            !s.configured -> "Set up the computer below"
            s.error != null -> s.error
            s.fetchedAt > 0 -> "Updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.fetchedAt))
            else -> ""
        }
        list.removeAllViews()
        val snapshot = s.forFace().first ?: return
        if (snapshot.sessions.isEmpty()) list.addView(label("No agents in the last 2 hours", 14f, INK2))
        for (session in snapshot.sessions) list.addView(row(session))
        list.addView(label("${snapshot.doneToday} finished today", 13f, DONE).apply { setPadding(0, (8 * dp).toInt(), 0, 0) })
    }

    private fun row(s: Session): View {
        val agent = Agent.entries.firstOrNull { it.key == s.agent }
        val name = (agent?.fullName ?: s.agent) + if (s.project.isEmpty()) "" else " · ${s.project}"
        val steps = if (s.total > 0) "${s.done}/${s.total} · " else ""
        val (line, color) = when {
            s.waiting -> ("Needs you" + if (s.detail.isEmpty()) "" else ": ${s.detail}") to WAIT
            s.finished -> "Finished · ${s.activity}" to DONE
            else -> "$steps${s.doing}" to INK
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (10 * dp).toInt(), 0, 0)
            addView(label(name, 14f, if (agent == Agent.CODEX) CODEX else CLAUDE, bold = true))
            addView(label(line, 13f, color))
        }
    }

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine(true)
        textSize = 14f
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (10 * dp).toInt() }
    }

    private companion object {
        // The face's colours: orange for Claude Code, blue for Codex, amber when an agent needs you, green when done.
        const val CLAUDE = 0xFF_FF8A3D.toInt()
        const val CODEX = 0xFF_4DA8FF.toInt()
        const val WAIT = 0xFF_FFC53D.toInt()
        const val DONE = 0xFF_3DDC84.toInt()
        const val INK = 0xFF_ECEBFF.toInt()
        const val INK2 = 0xFF_9AA0AE.toInt()
    }
}
