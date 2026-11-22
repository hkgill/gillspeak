package dev.hkgill.gillspeak

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * "Share to gillspeak" for audio: a call recording or voice memo shared from another app is transcribed on the phone
 * ([FileTranscript]) and shown here, with timestamps, to copy or share on. In the settings screen's look.
 */
class TranscribeActivity : Activity() {
    private lateinit var t: Look
    private lateinit var f: Fonts
    private val handler = Handler(Looper.getMainLooper())
    private val poll = Runnable { render() }

    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var progress: FrameLayout
    private lateinit var progressFill: View
    private lateinit var actions: LinearLayout
    private lateinit var note: TextView
    private lateinit var body: LinearLayout
    private var shownJob: FileTranscript? = null
    private var shownPieces = 0
    private var shownState: FileTranscript.State? = null

    private val back = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { leave() } else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        f = Fonts(this)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        t = if (night) Look.DARK else Look.LIGHT
        actionBar?.hide()

        title = text("Transcript", 28f, t.ink, f.heavy).apply { isAccessibilityHeading = true }
        status = text("", 15f, t.ink2).apply { setPadding(0, dp(6), 0, 0) }
        progressFill = View(this).apply { background = rounded(t.accT, dp(3).toFloat()) }
        progress = FrameLayout(this).apply {
            background = rounded(t.field, dp(3).toFloat())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(progressFill, FrameLayout.LayoutParams(0, MATCH_PARENT))
        }
        actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        note = text("", 14f, t.ink2).apply { setLineSpacing(0f, 1.1f) }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(t.card, dp(24).toFloat())
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
            addView(LinearLayout(this@TranscribeActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(LogoView(this@TranscribeActivity), LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
                addView(title)
            })
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
            addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(6)).apply { topMargin = dp(12) })
            addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8); marginStart = -dp(4) })
            addView(note, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
            addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(column)
            setBackgroundColor(t.bg)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(scroll)
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (night) 0 else lightBars, lightBars)

        // Recreated (rotation) or reopened from Recents: show the job there is rather than starting the share again.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back!!)
        render()
    }

    override fun onPause() {
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back!!)
        handler.removeCallbacks(poll)
        super.onPause()
    }

    @Deprecated("Android 12 and older; newer versions use the OnBackInvokedCallback")
    override fun onBackPressed() = leave()

    /** Back while transcribing asks first: leaving stops it. */
    private fun leave() {
        val job = FileTranscript.current
        if (job == null || job.state != FileTranscript.State.Running) return finish()
        AlertDialog.Builder(this)
            .setTitle("Stop transcribing?")
            .setMessage("What's transcribed so far will be lost.")
            .setPositiveButton("Stop") { _, _ -> job.cancel(); finish() }
            .setNegativeButton("Keep going", null)
            .show()
    }

    private fun handle(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (uri == null) return toast("Nothing to transcribe in that share")
        val running = FileTranscript.current?.takeIf { it.state == FileTranscript.State.Running }
        if (running != null) return toast("Still transcribing ${running.name}")
        FileTranscript.start(this, uri, displayName(uri))
        render()
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "Recording"

    // ---- Drawing ----

    private fun render() {
        handler.removeCallbacks(poll)
        val job = FileTranscript.current
        if (job == null) {
            status.text = "Share a call recording or voice memo to ${Settings.APP_NAME} to transcribe it here."
            progress.visibility = View.GONE
            actions.visibility = View.GONE
            note.visibility = View.GONE
            body.visibility = View.GONE
            return
        }
        if (job !== shownJob) {
            shownJob = job
            shownPieces = 0
            shownState = null
            body.removeAllViews()
        }
        val state = job.state
        val running = state == FileTranscript.State.Running
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pieces = job.pieces
        val heard = pieces.isNotEmpty()
        status.setTextColor(if (state is FileTranscript.State.Failed) t.attT else t.ink2)
        status.text = job.name + "\n" + when (state) {
            FileTranscript.State.Running -> when {
                job.durationMs > 0 -> "Transcribing… ${TranscriptText.stamp(job.doneMs)} of ${TranscriptText.stamp(job.durationMs)}"
                job.doneMs > 0 -> "Transcribing… ${TranscriptText.stamp(job.doneMs)} so far"
                else -> "Transcribing…"
            }
            FileTranscript.State.Done -> if (heard) "Done · ${TranscriptText.stamp(job.durationMs.coerceAtLeast(job.doneMs))} of audio" else "No speech found in this recording"
            FileTranscript.State.Cancelled -> "Stopped"
            is FileTranscript.State.Failed -> state.message
        }

        progress.visibility = if (running) View.VISIBLE else View.GONE
        if (running) {
            val fraction = if (job.durationMs > 0) (job.doneMs.toFloat() / job.durationMs).coerceIn(0f, 1f) else 0f
            progress.post {
                val w = (progress.width * fraction).toInt()
                if (progressFill.layoutParams.width != w) progressFill.layoutParams = FrameLayout.LayoutParams(w, MATCH_PARENT)
            }
        }

        if (state != shownState) {
            shownState = state
            actions.removeAllViews()
            if (running) {
                actions.addView(pill("Stop", filled = false) { job.cancel(); render() })
            } else if (heard) {
                actions.addView(pill("Copy", filled = true) { copy(job) })
                actions.addView(pill("Share", filled = false) { share(job) })
            }
            actions.visibility = if (actions.childCount > 0) View.VISIBLE else View.GONE
            note.text = if (running) "Keep this screen open until it finishes. Speech is recognised on the phone; the recording isn't copied or uploaded."
                else "Transcripts aren't saved. Copy or share this one to keep it."
            note.visibility = if (running || heard) View.VISIBLE else View.GONE
        }

        for (p in pieces.drop(shownPieces)) {
            body.addView(text(TranscriptText.stamp(p.startMs), 13f, t.accT, f.bold).apply { setPadding(0, dp(12), 0, dp(2)) })
            body.addView(text(p.text, 16f, t.ink).apply { setLineSpacing(0f, 1.15f); setTextIsSelectable(true) })
        }
        shownPieces = pieces.size
        body.visibility = if (heard) View.VISIBLE else View.GONE

        if (running) handler.postDelayed(poll, 500)
    }

    private fun copy(job: FileTranscript) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Transcript", TranscriptText.format(job.pieces)))
        if (Build.VERSION.SDK_INT < 33) toast("Copied") // newer versions confirm a copy themselves
    }

    private fun share(job: FileTranscript) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Transcript of ${job.name}")
            .putExtra(Intent.EXTRA_TEXT, TranscriptText.format(job.pieces))
        startActivity(Intent.createChooser(send, null))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun text(s: CharSequence, size: Float, color: Int, face: Typeface = f.regular) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = face
    }

    /** The settings screen's pill button: filled for the main action, outlined otherwise, in a 48 dp touch target. */
    private fun pill(label: String, filled: Boolean, onClick: () -> Unit): View {
        val inner = text(label, 15f, if (filled) t.onBtn else t.ink, f.bold).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                if (filled) setColor(t.btn) else setStroke(dp(1).coerceAtLeast(2), t.line2)
            }
            setPadding(dp(18), dp(10), dp(18), dp(10))
            foreground = RippleDrawable(ColorStateList.valueOf((t.ink and 0x00FFFFFF) or 0x1F000000), null, rounded(0xFF000000.toInt(), dp(999).toFloat()))
            isDuplicateParentStateEnabled = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return FrameLayout(this).apply {
            minimumHeight = dp(48)
            setPadding(dp(4), 0, dp(4), 0)
            addView(inner, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL))
            contentDescription = label
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }
}
