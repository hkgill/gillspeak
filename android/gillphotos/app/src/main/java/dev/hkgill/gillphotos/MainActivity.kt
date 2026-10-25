package dev.hkgill.gillphotos

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The gallery: a search box, document kinds, the people found, and a grid of photos. Typing "Sam at the beach last
 * summer" picks out the person and the time by rules, then matches "beach" by meaning. Until something is set up
 * (photo access, the search model, a first index), a card says what's missing.
 */
class MainActivity : Activity() {
    private lateinit var look: Look
    private lateinit var thumbs: Thumbs
    private val work = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var setup: LinearLayout
    private lateinit var search: EditText
    private lateinit var peopleRow: LinearLayout
    private lateinit var peopleScroll: View
    private lateinit var note: TextView
    private lateinit var grid: GridView
    private var ids: List<Long> = emptyList()
    private var importing: String? = null
    private var wasRunning = false

    private val onProgress: () -> Unit = {
        main.post {
            showStatus()
            val running = IndexService.progress.running
            if (wasRunning && !running) { refresh(); if (search.text.isEmpty()) showAll() }
            wasRunning = running
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = ids.size
        override fun getItem(position: Int) = ids[position]
        override fun getItemId(position: Int) = ids[position]
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = (convertView as? SquareImage) ?: SquareImage(this@MainActivity).apply {
                setBackgroundColor(look.field)
                contentDescription = "Photo"
            }
            thumbs.photo(v, ids[position], dp(160))
            return v
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        look = Look.of(this)
        thumbs = Thumbs(this)

        val title = label("gillphotos", look, 26f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        val more = TextView(this).apply {
            text = "⋯"
            textSize = 26f
            setTextColor(look.ink)
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            contentDescription = "More"
            setOnClickListener { more() }
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(more)
        }
        status = label("", look, 13f, dim = true)
        setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(look.card, dp(20).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        search = EditText(this).apply {
            hint = "Search: dog on a beach, Sam last summer, receipts"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(look.ink)
            setHintTextColor(look.ink2)
            textSize = 16f
            background = rounded(look.field, dp(24).toFloat())
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { find(text.toString()); true } else false
            }
        }
        val kinds = LinearLayout(this).apply {
            addView(chip("All photos") { search.setText(""); showAll() })
            addView(chip("Screenshots") { search.setText(""); searchFor { Search.screenshots(this@MainActivity) } })
            for ((name, prompt) in Search.KINDS) addView(chip(name) { search.setText(""); searchFor { Search.run(this@MainActivity, prompt) } })
        }
        peopleRow = LinearLayout(this)
        peopleScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(peopleRow)
            visibility = View.GONE
        }
        note = label("", look, 13f, dim = true)
        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(108)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(2)
            verticalSpacing = dp(2)
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                startActivity(Intent(this@MainActivity, PhotoActivity::class.java).putExtra(PhotoActivity.ID, ids[position]))
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(look.bg)
            fun pad(v: View, top: Int = 0) = addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                marginStart = dp(16); marginEnd = dp(16); topMargin = dp(top)
            })
            pad(header, 8)
            pad(status)
            pad(setup, 12)
            pad(search, 12)
            pad(HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; addView(kinds) }, 10)
            pad(peopleScroll, 10)
            pad(note, 8)
            addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (look == Look.DARK) 0 else lightBars, lightBars)

        if (!similar(intent)) showAll()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        similar(intent)
    }

    override fun onResume() {
        super.onResume()
        IndexService.listeners += onProgress
        wasRunning = IndexService.progress.running
        refresh()
    }

    override fun onPause() {
        IndexService.listeners -= onProgress
        super.onPause()
    }

    override fun onDestroy() {
        thumbs.close()
        work.shutdownNow()
        super.onDestroy()
    }

    /** "More like this" from a photo. */
    private fun similar(intent: Intent?): Boolean {
        val id = intent?.getLongExtra(SIMILAR, -1L) ?: -1L
        if (id < 0) return false
        search.setText("")
        searchFor { Search.similar(this, id) }
        return true
    }

    private fun chip(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(look.ink)
        gravity = Gravity.CENTER
        minHeight = dp(40)
        setPadding(dp(14), 0, dp(14), 0)
        background = rounded(look.card, dp(20).toFloat())
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun find(text: String) {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        if (text.isBlank()) return showAll()
        searchFor { Search.run(this, text) }
    }

    /** Runs a search off the main thread and shows what it found. */
    private fun searchFor(run: () -> Search.Result) {
        note.text = "Searching…"
        work.execute {
            val r = runCatching(run).getOrElse { Search.Result(emptyList(), "Search failed: ${it.message ?: it.javaClass.simpleName}") }
            main.post { show(r) }
        }
    }

    private fun show(r: Search.Result) {
        ids = r.ids
        note.text = r.note
        adapter.notifyDataSetChanged()
        grid.setSelection(0)
    }

    private fun showAll() {
        if (Library.access(this) == Library.Access.NONE) return show(Search.Result(emptyList(), ""))
        searchFor { Library.all(this).map { it.id }.let { Search.Result(it, "${it.size} photos") } }
    }

    // ---- Status, setup and people ----

    private fun refresh() {
        buildSetup()
        showStatus()
        work.execute {
            val people = Store.get(this).people()
            main.post { showPeople(people) }
        }
    }

    private fun showStatus() {
        importing?.let { status.text = it; return }
        val p = IndexService.progress
        work.execute {
            val c = Store.get(this).counts()
            val text = buildString {
                if (c.photos == 0) append("Not indexed yet")
                else append("${c.photos} photos · ${c.embedded} searchable by meaning · ${c.faced} checked for ${c.faces} faces")
                if (p.running) append("\nIndexing ${p.done} of ${p.total}")
                if (p.done > 0) append("\n${p.timing()}")
                if (p.note.isNotEmpty()) append("\n${p.note}")
            }
            main.post { if (importing == null) status.text = text }
        }
    }

    private fun buildSetup() {
        setup.removeAllViews()
        val access = Library.access(this)
        val running = IndexService.progress.running
        fun line(text: String) = setup.addView(label(text, look, 14f).apply { setPadding(0, dp(4), 0, dp(8)) })
        fun buttons(vararg b: View) = setup.addView(LinearLayout(this).apply {
            b.forEach { addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) }) }
        })
        when (access) {
            Library.Access.NONE -> {
                line("gillphotos needs to see your photos. Everything it learns stays on this phone.")
                buttons(pill("Allow photos", look, main = true) { requestPermissions(Library.permissions(), REQ_PHOTOS) })
            }
            Library.Access.SOME -> {
                line("You've shared some of your photos. Search only covers those.")
                buttons(pill("Choose more", look) { requestPermissions(Library.permissions(), REQ_PHOTOS) })
            }
            Library.Access.ALL -> Unit
        }
        if (!Embedder.isReady(this) && importing == null) {
            line(
                "Searching by meaning needs EmbeddingGemma 2 (text + vision, about 0.4 GB). Google's file needs a Hugging " +
                    "Face sign-in, so download the .litertlm on this phone, then import it. Faces work without it.",
            )
            buttons(
                pill("Get it", look) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MODEL_PAGE))) },
                pill("Import file", look, main = true) {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_MODEL)
                },
            )
        }
        if (access != Library.Access.NONE) {
            buttons(
                if (running) pill("Stop indexing", look) { IndexService.stop(this); main.postDelayed(::refresh, 500) }
                else pill("Index photos", look, main = true) { startIndex() },
            )
        }
        setup.visibility = if (setup.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun startIndex() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
        IndexService.start(this)
        main.postDelayed(::refresh, 500)
    }

    private fun showPeople(people: List<Store.Person>) {
        peopleRow.removeAllViews()
        for (p in people.take(40)) {
            val face = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(look.field)
                round()
            }
            thumbs.face(face, p.cover, dp(64))
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(face, LinearLayout.LayoutParams(dp(64), dp(64)))
                addView(label(p.name ?: "Name?", look, 12f, dim = p.name == null).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    if (p.name != null) typeface = Typeface.DEFAULT_BOLD
                }, LinearLayout.LayoutParams(dp(72), WRAP_CONTENT))
                contentDescription = "${p.name ?: "Unnamed person"}, ${p.faces} photos"
                setOnClickListener { search.setText(""); searchFor { Search.person(this@MainActivity, p.id) } }
                setOnLongClickListener { rename(p); true }
            }
            peopleRow.addView(cell, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        peopleScroll.visibility = if (people.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun rename(p: Store.Person) {
        val field = EditText(this).apply {
            setText(p.name ?: "")
            hint = "Name"
            isSingleLine = true
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        AlertDialog.Builder(this)
            .setTitle("Who is this?")
            .setMessage("Naming someone keeps their faces together. A name someone else already has joins the two.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = field.text.toString().trim()
                if (name.isNotEmpty()) work.execute { Store.get(this).rename(p.id, name); main.post(::refresh) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- More: settings and models ----

    private fun more() {
        val tokens = Embedder.tokens(this)
        val gemma = when (val s = Gemma.refresh(this)) {
            Gemma.Status.Missing -> "Download (2.6 GB, Wi-Fi)"
            is Gemma.Status.Downloading -> "downloading, ${s.done * 100 / s.total}%${if (s.waitingForWifi) ", waiting for Wi-Fi" else ""}"
            Gemma.Status.Installing -> "checking the download"
            Gemma.Status.Ready -> "ready (tap to delete)"
            is Gemma.Status.Failed -> "failed: ${s.reason} (tap to retry)"
        }
        val items = arrayOf(
            "Photo detail for search: ${if (tokens == 0) "model default" else "$tokens image tokens"}",
            "Gemma 4 E2B for questions about a photo: $gemma",
            "Group faces again",
            "Search model: ${Embedder.describe(this) ?: "not imported"}${if (Embedder.isReady(this)) " (tap to remove)" else ""}",
        )
        AlertDialog.Builder(this).setTitle("gillphotos").setItems(items) { _, which ->
            when (which) {
                0 -> chooseTokens()
                1 -> gemmaAction()
                2 -> regroup()
                3 -> if (Embedder.isReady(this)) confirm("Remove the search model? Photos will need indexing again to search by meaning.") {
                    work.execute { Embedder.delete(this); Store.get(this).clearVectors(); Search.reset(); main.post(::refresh) }
                }
            }
        }.show()
    }

    private fun chooseTokens() {
        val options = intArrayOf(0, 70, 280, 560)
        val names = arrayOf("Model default", "70 tokens: fastest", "280 tokens", "560 tokens: small text")
        AlertDialog.Builder(this)
            .setTitle("Photo detail for search")
            .setSingleChoiceItems(names, options.indexOf(Embedder.tokens(this)).coerceAtLeast(0)) { d, which ->
                Embedder.setTokens(this, options[which])
                d.dismiss()
                Toast.makeText(this, "Used for photos indexed from now on", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun gemmaAction() = when (Gemma.refresh(this)) {
        Gemma.Status.Ready -> confirm("Delete Gemma 4 E2B (2.6 GB)?") { work.execute { Gemma.delete(this) } }
        is Gemma.Status.Downloading -> confirm("Stop the Gemma download?") { Gemma.cancel(this) }
        Gemma.Status.Installing -> Unit
        else -> { Gemma.download(this); Toast.makeText(this, "Downloading Gemma 4 E2B over Wi-Fi", Toast.LENGTH_SHORT).show() }
    }

    private fun regroup() {
        note.text = "Grouping faces…"
        work.execute {
            val store = Store.get(this)
            val g = dev.hkgill.gillphotos.core.People.group(store.facesToGroup())
            store.saveGrouping(g)
            main.post { note.text = "${g.groups.size} unnamed groups"; refresh() }
        }
    }

    private fun confirm(message: String, yes: () -> Unit) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK") { _, _ -> yes() }.setNegativeButton("Cancel", null).show()
    }

    // ---- Results from the system ----

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PHOTOS) { refresh(); showAll() }
    }

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MODEL && resultCode == RESULT_OK) data?.data?.let(::importModel)
    }

    private fun importModel(uri: Uri) {
        var name = "model"
        var size = 0L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) }
        }
        if (!name.endsWith(".litertlm")) {
            Toast.makeText(this, "Pick the EmbeddingGemma 2 .litertlm file", Toast.LENGTH_LONG).show()
            return
        }
        importing = "Importing $name…"
        refresh()
        var last = 0L
        thread(name = "import") {
            val result = runCatching {
                Embedder.import(this, uri, name) { done ->
                    if (done - last > 16 shl 20) {
                        last = done
                        val text = "Importing $name: ${done shr 20} of ${size shr 20} MB"
                        main.post { importing = text; status.text = text }
                    }
                }
                Store.get(this).clearVectors()
                Search.reset()
            }
            main.post {
                importing = null
                refresh()
                result.onSuccess {
                    Toast.makeText(this, "Search model ready (${Embedder.backend}). Tap Index photos.", Toast.LENGTH_LONG).show()
                }.onFailure {
                    AlertDialog.Builder(this).setTitle("Couldn't use that model").setMessage(it.message ?: it.javaClass.simpleName)
                        .setPositiveButton("OK", null).show()
                }
            }
        }
    }

    companion object {
        const val SIMILAR = "similar"
        private const val REQ_PHOTOS = 1
        private const val REQ_MODEL = 2
        private const val REQ_NOTIFY = 3
        private const val MODEL_PAGE = "https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm"
    }
}
