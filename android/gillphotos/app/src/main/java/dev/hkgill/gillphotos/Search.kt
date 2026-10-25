package dev.hkgill.gillphotos

import android.content.Context
import android.os.SystemClock
import dev.hkgill.gillphotos.core.Queries
import dev.hkgill.gillphotos.core.VectorIndex
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/**
 * Answers searches. The rules in [Queries] pick out people and a time; the rest is matched by meaning against every
 * photo's EmbeddingGemma vector, kept in memory. The index service adds vectors as it goes, so search works while
 * indexing. All calls are for a worker thread.
 */
object Search {
    class Result(val ids: List<Long>, val note: String)

    /** Document kinds as searches by meaning. Screenshots are known from their folder instead. */
    val KINDS = listOf(
        "Documents" to "a printed document or letter",
        "Receipts" to "a shop receipt",
        "IDs and cards" to "an ID card, passport or driver licence",
        "Notes" to "handwritten notes on paper",
        "Whiteboards" to "a whiteboard with writing",
    )

    private const val MAX = 200
    private val lock = Any()
    private var index: VectorIndex? = null

    private fun index(context: Context): VectorIndex? = synchronized(lock) {
        index ?: run {
            var built: VectorIndex? = null
            Store.get(context).vectors { id, bytes, scale ->
                val i = built ?: VectorIndex(bytes.size).also { built = it }
                if (bytes.size == i.dims) i.put(id, bytes, scale)
            }
            built?.also { index = it }
        }
    }

    /** A new vector from the index service. */
    fun added(id: Long, bytes: ByteArray, scale: Float) = synchronized(lock) {
        index?.takeIf { it.dims == bytes.size }?.put(id, bytes, scale)
    }

    /** Forgets the vectors in memory (another model was imported). */
    fun reset() = synchronized(lock) { index = null }

    fun run(context: Context, input: String): Result {
        val t0 = SystemClock.uptimeMillis()
        val store = Store.get(context)
        val q = Queries.parse(input, store.names(), LocalDate.now(), ZoneId.systemDefault(), southern())
        // Everyone named must be in the photo.
        val withPeople = q.people.map { store.photosWith(listOf(it)) }.reduceOrNull { a, b -> a intersect b }
        val taken = store.photos()
        val takenAt = taken.toMap()
        val from = q.from
        val until = q.until
        fun allowed(id: Long): Boolean {
            if (withPeople != null && id !in withPeople) return false
            val t = takenAt[id] ?: return false
            return (from == null || t >= from) && (until == null || t < until)
        }

        val parts = ArrayList<String>()
        q.people.forEach { parts += store.name(it) ?: "?" }
        if (from != null && until != null) {
            val f = DateFormat.getDateInstance(DateFormat.MEDIUM)
            parts += "${f.format(Date(from))} – ${f.format(Date(until - 1))}"
        }
        if (q.text.isBlank()) {
            val ids = taken.map { it.first }.filter(::allowed)
            return Result(ids, (parts + "${ids.size} photos").joinToString(" · "))
        }
        if (!Embedder.isReady(context)) return Result(emptyList(), "Import the search model to search by meaning")
        val vectors = index(context) ?: return Result(emptyList(), "No photos indexed yet: tap Index photos")
        val v = Embedder.text(context, q.text)
        val hits = synchronized(lock) { vectors.search(v, MAX, ::allowed) }
        parts += "“${q.text}”"
        parts += "${hits.size} closest of ${vectors.size} indexed · ${SystemClock.uptimeMillis() - t0} ms"
        return Result(hits.map { it.id }, parts.joinToString(" · "))
    }

    /** Photos that look like [id], closest first. */
    fun similar(context: Context, id: Long): Result {
        val vectors = index(context) ?: return Result(emptyList(), "No photos indexed yet")
        val v = synchronized(lock) { vectors.vector(id) } ?: return Result(emptyList(), "That photo isn't indexed yet")
        val hits = synchronized(lock) { vectors.search(normalizeCopy(v), MAX + 1) { it != id } }
        return Result(hits.map { it.id }, "More like this · ${hits.size} photos")
    }

    fun screenshots(context: Context): Result {
        val store = Store.get(context)
        val shots = store.screenshots()
        val ids = store.photos().map { it.first }.filter { it in shots }
        return Result(ids, "Screenshots · ${ids.size}")
    }

    fun person(context: Context, person: Long): Result {
        val store = Store.get(context)
        val with = store.photosWith(listOf(person))
        val ids = store.photos().map { it.first }.filter { it in with }
        return Result(ids, "${store.name(person) ?: "Someone"} · ${ids.size} photos")
    }

    private fun normalizeCopy(v: FloatArray) = dev.hkgill.gillphotos.core.normalize(v.copyOf())

    /** Seasons turn round south of the equator: summer is December to February. */
    private fun southern(): Boolean {
        val zone = ZoneId.systemDefault().id
        return zone.startsWith("Australia/") || zone.startsWith("America/Argentina/") || zone in setOf(
            "Pacific/Auckland", "Pacific/Chatham", "Africa/Johannesburg", "America/Santiago", "America/Sao_Paulo",
            "America/Montevideo", "America/Asuncion",
        )
    }
}
