package dev.hkgill.gillphotos

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var placesRow: LinearLayout
    private lateinit var placesScroll: View
    private lateinit var queryRow: LinearLayout
    private lateinit var note: TextView
    private lateinit var grid: GridView
    private var ids: List<Long> = emptyList()
    private var wasRunning = false
    private var lastNote = ""

    private val onProgress: () -> Unit = {
        main.post {
            showStatus()
            val running = IndexService.progress.running
            val note = IndexService.progress.note
            if (note != lastNote) { lastNote = note; refresh() } // faces were grouped: new people to show
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

        val title = label("gillphotos", look, 28f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        // The promise, always in view: nothing here leaves the phone.
        val badge = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(look.accSoft, dp(16).toFloat())
            setPadding(dp(10), dp(6), dp(12), dp(6))
            addView(Icon(this@MainActivity, Icon.Kind.LOCK, look.accT), LinearLayout.LayoutParams(dp(16), dp(16)))
            addView(label("On this phone", look, 13f, bold = true).apply { setTextColor(look.accT); setPadding(dp(6), 0, 0, 0) })
            contentDescription = "Everything stays on this phone: gillphotos has no internet access"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("On this phone")
                    .setMessage("gillphotos has no internet permission, so Android blocks it from the network. Your photos, faces, places and searches never leave this phone, and aren't backed up or copied to a new phone.")
                    .setPositiveButton("OK", null).show()
            }
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(badge, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
            addView(pill("Settings", look) { more() })
        }
        status = label("", look, 13f, dim = true)
        setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        search = EditText(this).apply {
            hint = "Search: dog on a beach, Sam in Bondi"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(look.ink)
            setHintTextColor(look.ink2)
            textSize = 16f
            background = null
            setPadding(dp(10), dp(12), dp(8), dp(12))
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { find(text.toString()); true } else false
            }
        }
        val searchBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(16), 0, dp(8), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(look.card)
                setStroke(dp(1), look.line)
            }
            addView(Icon(this@MainActivity, Icon.Kind.SEARCH, look.ink2), LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(search, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        queryRow = LinearLayout(this).apply { visibility = View.GONE }
        val kinds = LinearLayout(this).apply {
            addView(chip("All photos") { search.setText(""); showAll() })
            addView(chip("Screenshots") { search.setText(""); searchFor { Search.screenshots(this@MainActivity) } })
            for ((name, prompt) in Search.KINDS) addView(chip(name) { search.setText(""); searchFor { Search.run(this@MainActivity, prompt) } })
        }
        peopleRow = LinearLayout(this)
        peopleScroll = section("People", peopleRow)
        placesRow = LinearLayout(this)
        placesScroll = section("Places", placesRow)
        note = label("", look, 13f, dim = true)
        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(120)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(3)
            verticalSpacing = dp(3)
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                startActivity(Intent(this@MainActivity, PhotoActivity::class.java).putExtra(PhotoActivity.ID, ids[position]))
            }
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fun pad(v: View, top: Int = 0) = addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                marginStart = dp(16); marginEnd = dp(16); topMargin = dp(top)
            })
            pad(header, 8)
            pad(status, 6)
            pad(setup, 6)
            pad(searchBox, 12)
            pad(HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; addView(queryRow) }, 10)
            pad(peopleScroll, 12)
            pad(placesScroll, 10)
            pad(HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; addView(kinds) }, 12)
            pad(note, 10)
        }
        // The top part scrolls away on short screens instead of squeezing the grid to nothing.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(look.bg)
            addView(android.widget.ScrollView(this@MainActivity).apply { addView(top) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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
        // Indexing that an app update or Android's time limit cut short carries on.
        if (!wasRunning && IndexService.wanted(this) && Library.access(this) != Library.Access.NONE) IndexService.start(this)
        refresh()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (Embedder.model.refresh(this@MainActivity) is PinnedModel.Status.Adding) {
                showStatus()
                main.postDelayed(this, 1000)
            } else if (setupShowsDownload) {
                refresh() // finished: drop the progress line
            }
        }
    }
    private var setupShowsDownload = false

    override fun onPause() {
        main.removeCallbacks(poll)
        setupShowsDownload = false
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

    /** A titled, sideways-scrolling row (People, Places); hidden until it has something in it. */
    private fun section(title: String, row: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(label(title, look, 13f, bold = true, dim = true))
        addView(HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; addView(row) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
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
        // How the search was read: the person, the time, the place, the words matched by meaning.
        queryRow.removeAllViews()
        for (c in r.chips) queryRow.addView(label(c, look, 13f, bold = true).apply {
            setTextColor(look.bg)
            background = rounded(look.ink, dp(14).toFloat())
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        queryRow.visibility = if (r.chips.isEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
        grid.setSelection(0)
    }

    private fun showAll() {
        if (Library.access(this) == Library.Access.NONE) return show(Search.Result(emptyList(), ""))
        searchFor { Library.all(this).map { it.id }.let { Search.Result(it, "${it.size} photos") } }
    }

    // ---- Status, setup and people ----

    private fun refresh() {
        showStatus()
        work.execute {
            val store = Store.get(this)
            val people = store.people()
            val places = store.places()
            main.post { showPeople(people); showPlaces(places) }
        }
    }

    /** The counts line and the setup card, from the database (on the worker) and the index's progress. */
    private fun showStatus() {
        work.execute {
            val c = Store.get(this).counts()
            main.post { buildSetup(c) }
        }
    }

    /**
     * The first-run card from the design: three numbered steps (allow photos, add the search model, index), each ticked
     * when done. It goes away once everything is indexed.
     */
    private fun buildSetup(c: Store.Counts) {
        val access = Library.access(this)
        val p = IndexService.progress
        val model = Embedder.model.refresh(this)
        val usable = (c.photos - c.failed).coerceAtLeast(0)
        val indexed = minOf(if (model is PinnedModel.Status.Ready) c.embedded else c.faced, c.faced).coerceAtMost(usable)
        val allIndexed = c.photos > 0 && indexed >= usable && model is PinnedModel.Status.Ready
        status.text = if (c.photos == 0) "" else "${c.photos} photos · ${c.embedded} searchable · ${c.faces} faces · ${c.located} with a place"

        setup.removeAllViews()
        if (access == Library.Access.ALL && model is PinnedModel.Status.Ready && allIndexed && !p.running) {
            setup.visibility = View.GONE
            return
        }
        setup.visibility = View.VISIBLE
        step(1, "Allow photos", access == Library.Access.ALL) {
            when (access) {
                Library.Access.NONE -> {
                    it.addView(body("gillphotos needs to see your photos, and where they were taken. Everything it learns stays on this phone."))
                    it.addView(buttons(pill("Allow photos", look, main = true) { requestPermissions(Library.permissions(), REQ_PHOTOS) }))
                }
                Library.Access.SOME -> {
                    it.addView(body("You've shared some of your photos. Search only covers those."))
                    it.addView(buttons(pill("Choose more", look) { requestPermissions(Library.permissions(), REQ_PHOTOS) }))
                }
                Library.Access.ALL -> if (!Library.canReadLocation(this)) {
                    it.addView(body("Allow photo locations too, to search by place."))
                    it.addView(buttons(pill("Allow locations", look) { requestPermissions(Library.permissions(), REQ_PHOTOS) }))
                }
            }
        }
        step(2, "Add the search model", model is PinnedModel.Status.Ready) {
            when (model) {
                PinnedModel.Status.Ready -> Unit
                is PinnedModel.Status.Adding -> it.addView(progressBar(model.done.toFloat() / model.total, "Checking and adding: ${model.done * 100 / model.total}%"))
                else -> {
                    it.addView(body(
                        (if (model is PinnedModel.Status.Failed) "Couldn't add it: ${model.reason}. " else "") +
                            "${Embedder.model.megabytes} MB. Your browser downloads it, then you add the file. gillphotos checks it's the exact file.",
                    ))
                    it.addView(buttons(
                        pill("Download in browser", look) { Embedder.model.openDownload(this) },
                        pill("Add the file", look, main = true) { startActivityForResult(Embedder.model.pickIntent(), REQ_SEARCH_MODEL) },
                    ))
                }
            }
        }
        if (access != Library.Access.NONE) step(3, "Index your photos", allIndexed && !p.running) {
            it.addView(body("gillphotos looks at each photo once to learn what's in it, who's in it and where, so searches answer in a moment. Best while charging."))
            if (c.photos > 0) {
                val line = buildString {
                    append("$indexed of $usable")
                    if (p.running && p.done > 0) append(" · ${p.timing()}")
                    if (p.note.isNotEmpty()) append("\n${p.note}")
                }
                it.addView(progressBar(if (usable == 0) 0f else indexed.toFloat() / usable, line))
            }
            it.addView(buttons(
                if (p.running) pill("Stop indexing", look) { IndexService.stop(this); main.postDelayed(::refresh, 500) }
                else pill(if (indexed > 0) "Carry on indexing" else "Index photos", look, main = true) { startIndex() },
            ))
        }
        val adding = model is PinnedModel.Status.Adding
        if (adding && !setupShowsDownload) main.postDelayed(poll, 1000)
        setupShowsDownload = adding
    }

    /** One numbered step card: a night circle with the number, the title, a tick once done, and its content. */
    private fun step(n: Int, title: String, done: Boolean, content: (LinearLayout) -> Unit) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(n.toString(), look, 15f, bold = true).apply {
                setTextColor(Look.LIME)
                gravity = Gravity.CENTER
                background = rounded(0xFF_111318.toInt(), dp(16).toFloat())
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(label(title, look, 16f, bold = true).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (done) addView(Icon(this@MainActivity, Icon.Kind.CHECK, look.accT, 3f), LinearLayout.LayoutParams(dp(22), dp(22)))
        }
        body.addView(head)
        if (!done) {
            val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(44), 0, 0, 0) }
            content(inner)
            body.addView(inner)
        }
        body.background = rounded(look.card, dp(20).toFloat())
        body.setPadding(dp(16), dp(14), dp(16), dp(14))
        setup.addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun body(text: String) = label(text, look, 14f, dim = true).apply { setPadding(0, dp(6), 0, dp(4)) }

    private fun buttons(vararg b: View) = LinearLayout(this).apply {
        setPadding(0, dp(6), 0, 0)
        b.forEach { addView(it, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) }) }
    }

    private fun progressBar(fraction: Float, text: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(4))
        val track = android.widget.FrameLayout(this@MainActivity).apply { background = rounded(look.field, dp(4).toFloat()) }
        track.addView(View(this@MainActivity).apply { background = rounded(look.accT, dp(4).toFloat()) },
            android.widget.FrameLayout.LayoutParams(0, dp(8)))
        track.post { track.getChildAt(0).layoutParams.width = (track.width * fraction.coerceIn(0f, 1f)).toInt(); track.getChildAt(0).requestLayout() }
        addView(track, LinearLayout.LayoutParams(MATCH_PARENT, dp(8)))
        addView(label(text, look, 13f, dim = true).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun showPlaces(places: List<Pair<String, Int>>) {
        placesRow.removeAllViews()
        for ((name, n) in places.take(30)) {
            placesRow.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(40)
                setPadding(dp(12), 0, dp(14), 0)
                background = rounded(look.card, dp(20).toFloat())
                addView(Icon(this@MainActivity, Icon.Kind.PIN, look.ink2), LinearLayout.LayoutParams(dp(16), dp(16)))
                addView(label("$name  ", look, 14f).apply { setPadding(dp(6), 0, 0, 0) })
                addView(label(n.toString(), look, 13f, dim = true))
                contentDescription = "$name, $n photos"
                setOnClickListener { search.setText(""); searchFor { Search.place(this@MainActivity, name) } }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) })
        }
        placesScroll.visibility = if (places.isEmpty()) View.GONE else View.VISIBLE
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
        val gemma = Gemma.model.describe(this) + if (Gemma.model.isReady(this)) " (tap to delete)" else " (tap to download, 2.6 GB)"
        val items = arrayOf(
            "Photo detail for search: ${if (tokens == 0) "model default" else "$tokens image tokens"}",
            "Gemma 4 E2B for questions about a photo: $gemma",
            "Group faces again",
            "Search model, EmbeddingGemma 2: ${Embedder.model.describe(this)}${if (Embedder.isReady(this)) " (tap to delete)" else ""}",
        )
        AlertDialog.Builder(this).setTitle("Settings").setItems(items) { _, which ->
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
        val options = intArrayOf(0, 70, 140)
        val names = arrayOf("Model default", "70 tokens: fastest", "140 tokens: more detail")
        AlertDialog.Builder(this)
            .setTitle("Photo detail for search")
            .setSingleChoiceItems(names, options.indexOf(Embedder.tokens(this)).coerceAtLeast(0)) { d, which ->
                Embedder.setTokens(this, options[which])
                d.dismiss()
                Toast.makeText(this, "Used for photos indexed from now on", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun gemmaAction() {
        when (Gemma.model.refresh(this)) {
        PinnedModel.Status.Ready -> confirm("Delete Gemma 4 E2B (2.6 GB)?") { work.execute { Gemma.delete(this) } }
        is PinnedModel.Status.Adding -> Unit
        else -> AlertDialog.Builder(this)
            .setTitle("Gemma 4 E2B")
            .setMessage("2.6 GB, for questions about a photo. gillphotos has no internet access, so your browser downloads it; then add the file here.")
            .setPositiveButton("Add the file") { _, _ -> startActivityForResult(Gemma.model.pickIntent(), REQ_GEMMA) }
            .setNeutralButton("Download in browser") { _, _ -> Gemma.model.openDownload(this) }
            .show()
        }
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

    @Deprecated("Activity results without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_SEARCH_MODEL -> Embedder.model.add(this, uri) { Search.reset(); main.post(::refresh) }
            REQ_GEMMA -> Gemma.model.add(this, uri) { main.post { Toast.makeText(this, "Gemma: ${Gemma.model.describe(this)}", Toast.LENGTH_LONG).show() } }
        }
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PHOTOS) { refresh(); showAll() }
    }

    companion object {
        const val SIMILAR = "similar"
        private const val REQ_PHOTOS = 1
        private const val REQ_NOTIFY = 3
        private const val REQ_SEARCH_MODEL = 4
        private const val REQ_GEMMA = 5
    }
}
