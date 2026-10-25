package dev.hkgill.gillphotos

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * One photo: who's in it (name a face, or take it out of a person), "More like this", and, when Gemma 4 E2B is
 * downloaded, a question about it. Gemma's answer comes with its time, which is what the research wants to know.
 */
class PhotoActivity : Activity() {
    private lateinit var look: Look
    private lateinit var thumbs: Thumbs
    private val work = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var id = -1L

    private lateinit var facesRow: LinearLayout
    private lateinit var ask: LinearLayout
    private var asking = false
    private val poll = object : Runnable {
        override fun run() {
            buildAsk()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        look = Look.of(this)
        thumbs = Thumbs(this)
        id = intent.getLongExtra(ID, -1L)

        val date = label("", look, 14f, dim = true).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(pill("Back", look) { finish() })
            addView(date.apply { setPadding(dp(12), 0, 0, 0) })
        }
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "The photo"
        }
        facesRow = LinearLayout(this)
        ask = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(look.card, dp(20).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fun add(v: View, top: Int) = addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                marginStart = dp(16); marginEnd = dp(16); topMargin = dp(top)
            })
            add(bar, 8)
            addView(image, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
            add(HorizontalScrollView(this@PhotoActivity).apply { isHorizontalScrollBarEnabled = false; addView(facesRow) }, 12)
            add(LinearLayout(this@PhotoActivity).apply {
                addView(pill("More like this", look) {
                    startActivity(Intent(this@PhotoActivity, MainActivity::class.java).putExtra(MainActivity.SIMILAR, id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                })
            }, 12)
            add(ask, 12)
            addView(View(this@PhotoActivity), LinearLayout.LayoutParams(MATCH_PARENT, dp(24)))
        }
        val root = ScrollView(this).apply {
            setBackgroundColor(look.bg)
            addView(column)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(root)
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (look == Look.DARK) 0 else lightBars, lightBars)

        val side = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).coerceAtMost(2048)
        work.execute {
            val picture = runCatching { Library.picture(this, id, side) }.getOrNull()
            val taken = Store.get(this).taken(id)
            main.post {
                image.setImageBitmap(picture)
                taken?.let { date.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
            }
        }
        showFaces()
    }

    override fun onResume() {
        super.onResume()
        main.post(poll)
    }

    override fun onPause() {
        main.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        thumbs.close()
        work.shutdown()
        super.onDestroy()
    }

    private fun showFaces() = work.execute {
        val store = Store.get(this)
        val faces = store.faces(id).map { it to it.person?.let(store::name) }
        main.post {
            facesRow.removeAllViews()
            for ((face, name) in faces) {
                val view = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(look.field); round() }
                thumbs.face(view, face, dp(56))
                facesRow.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(view, LinearLayout.LayoutParams(dp(56), dp(56)))
                    addView(label(name ?: "Name?", look, 12f, dim = name == null).apply {
                        gravity = Gravity.CENTER
                        maxLines = 1
                        if (name != null) typeface = Typeface.DEFAULT_BOLD
                    }, LinearLayout.LayoutParams(dp(68), WRAP_CONTENT))
                    contentDescription = name ?: "Unnamed face"
                    setOnClickListener { faceMenu(face, name) }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6) })
            }
            if (faces.isEmpty()) facesRow.addView(label("No faces found", look, 13f, dim = true))
        }
    }

    private fun faceMenu(face: Store.FaceRow, name: String?) {
        val items = if (name == null) arrayOf("Name this person") else arrayOf("Name this person", "Not $name")
        AlertDialog.Builder(this).setItems(items) { _, which ->
            if (which == 0) nameFace(face, name) else work.execute { Store.get(this).removeFace(face.id); showFaces() }
        }.show()
    }

    private fun nameFace(face: Store.FaceRow, name: String?) {
        val field = EditText(this).apply { setText(name ?: ""); hint = "Name"; isSingleLine = true }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        AlertDialog.Builder(this).setTitle("Who is this?").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = field.text.toString().trim()
                if (n.isNotEmpty()) work.execute { Store.get(this).nameFace(face.id, n); showFaces() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---- Ask Gemma ----

    private var built: String? = null

    /** The ask card for Gemma's current state; rebuilt only when that changes, so typing isn't lost. */
    private fun buildAsk() {
        val status = Gemma.model.refresh(this)
        val key = when (status) {
            is PinnedModel.Status.Downloading -> "downloading ${status.done * 100 / status.total} ${status.waitingForWifi}"
            else -> status.javaClass.simpleName
        }
        if (key == built) return
        built = key
        ask.removeAllViews()
        ask.addView(label("Ask Gemma about this photo", look, 16f, bold = true))
        when (status) {
            PinnedModel.Status.Ready -> askForm()
            PinnedModel.Status.Missing -> {
                ask.addView(label("Gemma 4 E2B runs on this phone and can describe the photo or read its text. It's a 2.6 GB download over Wi-Fi.", look, 14f, dim = true))
                ask.addView(pill("Download Gemma", look, main = true) { Gemma.model.download(this) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            }
            is PinnedModel.Status.Downloading -> ask.addView(label(
                "Downloading: ${status.done * 100 / status.total}%" + if (status.waitingForWifi) ", waiting for Wi-Fi" else "", look, 14f, dim = true,
            ))
            PinnedModel.Status.Installing -> ask.addView(label("Checking the download…", look, 14f, dim = true))
            is PinnedModel.Status.Failed -> {
                ask.addView(label("The download failed: ${status.reason}", look, 14f, dim = true))
                ask.addView(pill("Try again", look) { Gemma.model.download(this) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            }
        }
    }

    private fun askForm() {
        val question = EditText(this).apply {
            hint = "What's the total on this receipt?"
            setTextColor(look.ink)
            setHintTextColor(look.ink2)
            background = rounded(look.field, dp(16).toFloat())
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val answer = label("", look, 15f).apply { setTextIsSelectable(true) }
        val timing = label("", look, 12f, dim = true)
        fun go(q: String) {
            if (asking || q.isBlank()) return
            asking = true
            answer.text = "Thinking…"
            timing.text = ""
            work.execute {
                val result = runCatching { Gemma.ask(this, Library.jpeg(Library.picture(this, id, 1024)), q) }
                main.post {
                    asking = false
                    result.onSuccess {
                        answer.text = it.text
                        timing.text = "%.1f s".format((it.ms - it.loadMs) / 1000.0) + if (it.loadMs > 50) " (+ %.1f s loading Gemma)".format(it.loadMs / 1000.0) else ""
                    }.onFailure { answer.text = "Gemma couldn't answer: ${it.message ?: it.javaClass.simpleName}" }
                }
            }
        }
        val presets = LinearLayout(this).apply {
            for (q in listOf("Describe this photo", "Read all the text in it", "What kind of document is this?")) {
                addView(TextView(this@PhotoActivity).apply {
                    text = q
                    textSize = 13f
                    setTextColor(look.ink)
                    gravity = Gravity.CENTER
                    setPadding(dp(12), 0, dp(12), 0)
                    background = rounded(look.field, dp(18).toFloat())
                    setOnClickListener { question.setText(q); go(q) }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(36)).apply { marginEnd = dp(8) })
            }
        }
        ask.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(presets) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        ask.addView(question, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        ask.addView(pill("Ask", look, main = true) { go(question.text.toString()) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        ask.addView(answer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        ask.addView(timing)
    }

    companion object {
        const val ID = "id"
    }
}
