package dev.hkgill.gillphotos

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.hkgill.gillphotos.core.People
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What gillphotos has learnt about each photo, kept only on the phone: in app-private storage, never backed up.
 * Photos are MediaStore ids. A photo is indexed in two halves that can happen at different times: its EmbeddingGemma
 * vector (once that model is imported) and its faces (the face models are in the APK).
 */
class Store private constructor(context: Context) : SQLiteOpenHelper(context, "gillphotos.db", null, 1) {
    init {
        setWriteAheadLoggingEnabled(true) // searches read while the index service writes
    }

    /** A face, its box as fractions of the upright picture. */
    class FaceRow(val id: Long, val photo: Long, val x: Float, val y: Float, val w: Float, val h: Float, val score: Float, val person: Long?)

    class Person(val id: Long, val name: String?, val faces: Int, val cover: FaceRow)

    class Counts(val photos: Int, val embedded: Int, val faced: Int, val failed: Int, val faces: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE photos (id INTEGER PRIMARY KEY, taken INTEGER NOT NULL, modified INTEGER NOT NULL, " +
                "screenshot INTEGER NOT NULL, vector BLOB, scale REAL, faced INTEGER NOT NULL DEFAULT 0, failed INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL(
            "CREATE TABLE faces (id INTEGER PRIMARY KEY AUTOINCREMENT, photo INTEGER NOT NULL, x REAL, y REAL, w REAL, h REAL, " +
                "score REAL, vector BLOB NOT NULL, person INTEGER, named INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX faces_photo ON faces(photo)")
        db.execSQL("CREATE INDEX faces_person ON faces(person)")
        db.execSQL("CREATE TABLE people (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private inline fun <T> tx(block: SQLiteDatabase.() -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            return db.block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    /** Matches the table to what MediaStore has now: new photos to index, changed ones again, deleted ones gone. */
    fun sync(photos: List<Library.Photo>) = tx {
        val known = HashMap<Long, Long>()
        rawQuery("SELECT id, modified FROM photos", null).use { c -> while (c.moveToNext()) known[c.getLong(0)] = c.getLong(1) }
        for (p in photos) {
            val modified = known.remove(p.id)
            if (modified == p.modified) continue
            if (modified != null) delete("faces", "photo = ?", arrayOf(p.id.toString()))
            insertWithOnConflict("photos", null, ContentValues().apply {
                put("id", p.id); put("taken", p.taken); put("modified", p.modified); put("screenshot", if (p.screenshot) 1 else 0)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        for (gone in known.keys) {
            delete("photos", "id = ?", arrayOf(gone.toString()))
            delete("faces", "photo = ?", arrayOf(gone.toString()))
        }
    }

    /** Photos still to index, newest first: missing faces, or a vector when [vectors] is on. */
    fun pending(vectors: Boolean): List<Long> {
        val where = if (vectors) "failed = 0 AND (faced = 0 OR vector IS NULL)" else "failed = 0 AND faced = 0"
        return readableDatabase.rawQuery("SELECT id FROM photos WHERE $where ORDER BY taken DESC", null).use { c ->
            List(c.count) { c.moveToNext(); c.getLong(0) }
        }
    }

    fun needs(id: Long): Pair<Boolean, Boolean> =
        readableDatabase.rawQuery("SELECT vector IS NULL, faced = 0 FROM photos WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) (c.getInt(0) == 1) to (c.getInt(1) == 1) else false to false
        }

    fun saveVector(id: Long, bytes: ByteArray, scale: Float) {
        writableDatabase.update("photos", ContentValues().apply { put("vector", bytes); put("scale", scale) }, "id = ?", arrayOf(id.toString()))
    }

    /** Replaces the photo's faces: boxes as fractions of a [width] × [height] picture. */
    fun saveFaces(id: Long, width: Int, height: Int, found: List<dev.hkgill.gillphotos.core.FaceModels.Found>) = tx {
        delete("faces", "photo = ?", arrayOf(id.toString()))
        for (f in found) insert("faces", null, ContentValues().apply {
            put("photo", id)
            put("x", f.face.x / width); put("y", f.face.y / height); put("w", f.face.w / width); put("h", f.face.h / height)
            put("score", f.face.score)
            put("vector", floatsToBytes(f.vector))
        })
        update("photos", ContentValues().apply { put("faced", 1) }, "id = ?", arrayOf(id.toString()))
    }

    /** Drops every photo vector: another search model was imported, and vectors from two models don't compare. */
    fun clearVectors() {
        writableDatabase.execSQL("UPDATE photos SET vector = NULL, scale = NULL")
    }

    fun fail(id: Long) {
        writableDatabase.update("photos", ContentValues().apply { put("failed", 1) }, "id = ?", arrayOf(id.toString()))
    }

    fun counts(): Counts = readableDatabase.rawQuery(
        "SELECT COUNT(*), COUNT(vector), SUM(faced), SUM(failed), (SELECT COUNT(*) FROM faces) FROM photos", null,
    ).use { c -> c.moveToFirst(); Counts(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4)) }

    /** Every photo vector: id, bytes and scale. */
    fun vectors(each: (Long, ByteArray, Float) -> Unit) {
        readableDatabase.rawQuery("SELECT id, vector, scale FROM photos WHERE vector IS NOT NULL", null).use { c ->
            while (c.moveToNext()) each(c.getLong(0), c.getBlob(1), c.getFloat(2))
        }
    }

    /** Every photo with when it was taken, newest first, and which are screenshots. */
    fun photos(): List<Pair<Long, Long>> =
        readableDatabase.rawQuery("SELECT id, taken FROM photos ORDER BY taken DESC", null).use { c ->
            List(c.count) { c.moveToNext(); c.getLong(0) to c.getLong(1) }
        }

    fun screenshots(): Set<Long> = readableDatabase.rawQuery("SELECT id FROM photos WHERE screenshot = 1", null).use { c ->
        HashSet<Long>().apply { while (c.moveToNext()) add(c.getLong(0)) }
    }

    fun taken(id: Long): Long? = readableDatabase.rawQuery("SELECT taken FROM photos WHERE id = ?", arrayOf(id.toString())).use { c ->
        if (c.moveToFirst()) c.getLong(0) else null
    }

    // ---- People ----

    /** Every face for [People.group], with its person when the user named it. Faces taken out of a person are left out. */
    fun facesToGroup(): List<People.Face> =
        readableDatabase.rawQuery("SELECT id, vector, CASE WHEN named = 1 THEN person END FROM faces WHERE named >= 0 ORDER BY score DESC", null).use { c ->
            List(c.count) {
                c.moveToNext()
                People.Face(c.getLong(0), bytesToFloats(c.getBlob(1)), if (c.isNull(2)) null else c.getLong(2))
            }
        }

    /** Stores a fresh grouping: unnamed people are replaced; named people keep their faces and gain lookalikes. */
    fun saveGrouping(g: People.Grouping) = tx {
        execSQL("UPDATE faces SET person = NULL WHERE named = 0")
        execSQL("DELETE FROM people WHERE name IS NULL")
        for ((face, person) in g.named) execSQL("UPDATE faces SET person = ? WHERE id = ?", arrayOf(person, face))
        for (members in g.groups) {
            val person = insert("people", null, ContentValues().apply { putNull("name") })
            for (face in members) execSQL("UPDATE faces SET person = ? WHERE id = ?", arrayOf(person, face))
        }
    }

    /** Named people first, then the biggest groups; each with its clearest face. */
    fun people(): List<Person> = readableDatabase.rawQuery(
        "SELECT p.id, p.name, COUNT(f.id), " +
            "(SELECT id FROM faces WHERE person = p.id ORDER BY score * w DESC LIMIT 1) " +
            "FROM people p JOIN faces f ON f.person = p.id GROUP BY p.id " +
            "ORDER BY p.name IS NULL, COUNT(f.id) DESC",
        null,
    ).use { c ->
        class Row(val id: Long, val name: String?, val faces: Int, val cover: Long)
        val rows = List(c.count) { c.moveToNext(); Row(c.getLong(0), c.getString(1), c.getInt(2), c.getLong(3)) }
        rows.mapNotNull { r -> face(r.cover)?.let { Person(r.id, r.name, r.faces, it) } }
    }

    fun face(id: Long): FaceRow? = readableDatabase.rawQuery(
        "SELECT id, photo, x, y, w, h, score, person FROM faces WHERE id = ?", arrayOf(id.toString()),
    ).use { c -> if (c.moveToFirst()) faceRow(c) else null }

    fun faces(photo: Long): List<FaceRow> = readableDatabase.rawQuery(
        "SELECT id, photo, x, y, w, h, score, person FROM faces WHERE photo = ? ORDER BY x", arrayOf(photo.toString()),
    ).use { c -> List(c.count) { c.moveToNext(); faceRow(c) } }

    private fun faceRow(c: android.database.Cursor) = FaceRow(
        c.getLong(0), c.getLong(1), c.getFloat(2), c.getFloat(3), c.getFloat(4), c.getFloat(5), c.getFloat(6),
        if (c.isNull(7)) null else c.getLong(7),
    )

    /** Lower-case name → person, for reading searches. */
    fun names(): Map<String, Long> = readableDatabase.rawQuery("SELECT id, name FROM people WHERE name IS NOT NULL", null).use { c ->
        HashMap<String, Long>().apply { while (c.moveToNext()) put(c.getString(1).lowercase(), c.getLong(0)) }
    }

    fun name(person: Long): String? = readableDatabase.rawQuery("SELECT name FROM people WHERE id = ?", arrayOf(person.toString())).use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
    }

    /** Photos with any of [people] in them. */
    fun photosWith(people: List<Long>): Set<Long> {
        if (people.isEmpty()) return emptySet()
        val marks = people.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT DISTINCT photo FROM faces WHERE person IN ($marks)", people.map { it.toString() }.toTypedArray(),
        ).use { c -> HashSet<Long>().apply { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    /**
     * Names a person and confirms their faces, so later groupings keep them. A name someone else already has merges
     * the two. Returns the person who now has the name.
     */
    fun rename(person: Long, name: String): Long = tx {
        val clean = name.trim()
        val same = rawQuery("SELECT id FROM people WHERE name = ? COLLATE NOCASE AND id != ?", arrayOf(clean, person.toString())).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
        val keep = same ?: person
        if (same != null) {
            execSQL("UPDATE faces SET person = ? WHERE person = ?", arrayOf(same, person))
            delete("people", "id = ?", arrayOf(person.toString()))
        } else {
            update("people", ContentValues().apply { put("name", clean) }, "id = ?", arrayOf(person.toString()))
        }
        execSQL("UPDATE faces SET named = 1 WHERE person = ?", arrayOf(keep))
        keep
    }

    /** Names one face: a new person, or the existing one with that name. Returns the person. */
    fun nameFace(face: Long, name: String): Long = tx {
        val clean = name.trim()
        val person = rawQuery("SELECT id FROM people WHERE name = ? COLLATE NOCASE", arrayOf(clean)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        } ?: insert("people", null, ContentValues().apply { put("name", clean) })
        execSQL("UPDATE faces SET person = ?, named = 1 WHERE id = ?", arrayOf(person, face))
        person
    }

    /** "Not this person": the face leaves them and stays out of people from then on (named = -1). */
    fun removeFace(face: Long) {
        writableDatabase.execSQL("UPDATE faces SET person = NULL, named = -1 WHERE id = ?", arrayOf(face))
    }

    companion object {
        @Volatile private var instance: Store? = null

        fun get(context: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(context.applicationContext).also { instance = it }
        }

        fun floatsToBytes(v: FloatArray): ByteArray =
            ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(v) }.array()

        fun bytesToFloats(b: ByteArray): FloatArray =
            FloatArray(b.size / 4).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }
}
