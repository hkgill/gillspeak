package dev.hkgill.gillphotos.core

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every photo's EmbeddingGemma vector, kept as bytes: each number is a signed byte times the vector's own scale,
 * a quarter of the size of floats. Searching is a dot product with every photo: a few milliseconds for 10,000, so no
 * vector database is needed at this size. Not thread-safe: the caller adds and searches from one thread.
 */
class VectorIndex(val dims: Int) {
    class Hit(val id: Long, val score: Float)

    private var ids = LongArray(1024)
    private var scales = FloatArray(1024)
    private var data = ByteArray(1024 * dims)
    private val slot = HashMap<Long, Int>()

    val size get() = slot.size

    /** Adds or replaces [id]'s vector, as [quantize] made it. */
    fun put(id: Long, bytes: ByteArray, scale: Float) {
        require(bytes.size == dims) { "vector has ${bytes.size} numbers, the index $dims" }
        val i = slot.getOrPut(id) {
            if (slot.size == ids.size) grow()
            slot.size
        }
        ids[i] = id
        scales[i] = scale
        bytes.copyInto(data, i * dims)
    }

    fun contains(id: Long) = id in slot

    /** [id]'s vector back as floats, for "more like this". */
    fun vector(id: Long): FloatArray? {
        val i = slot[id] ?: return null
        return FloatArray(dims) { data[i * dims + it] * scales[i] }
    }

    /** The [k] photos closest to [query] (unit length), best first, among those [allow] lets through. */
    fun search(query: FloatArray, k: Int, allow: (Long) -> Boolean = { true }): List<Hit> {
        require(query.size == dims) { "query has ${query.size} numbers, the index $dims" }
        val best = PriorityQueue<Hit>(k + 1, compareBy { it.score })
        for (i in 0 until slot.size) {
            if (!allow(ids[i])) continue
            var s = 0f
            val base = i * dims
            for (d in 0 until dims) s += query[d] * data[base + d]
            val hit = Hit(ids[i], s * scales[i])
            if (best.size < k) best += hit
            else if (hit.score > best.peek().score) { best.poll(); best += hit }
        }
        return best.sortedByDescending { it.score }
    }

    private fun grow() {
        val n = ids.size * 2
        ids = ids.copyOf(n)
        scales = scales.copyOf(n)
        data = data.copyOf(n * dims)
    }

    companion object {
        /** A vector as bytes and the scale that turns them back: the largest number becomes ±127. */
        fun quantize(v: FloatArray): Pair<ByteArray, Float> {
            val max = v.maxOf { abs(it) }
            if (max == 0f) return ByteArray(v.size) to 0f
            val scale = max / 127f
            return ByteArray(v.size) { (v[it] / scale).roundToInt().coerceIn(-127, 127).toByte() } to scale
        }
    }
}
