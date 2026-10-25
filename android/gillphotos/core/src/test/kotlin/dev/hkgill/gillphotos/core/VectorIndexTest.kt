package dev.hkgill.gillphotos.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorIndexTest {
    private val random = Random(7)
    private fun unit(dims: Int) = normalize(FloatArray(dims) { random.nextFloat() * 2 - 1 })

    private fun VectorIndex.add(id: Long, v: FloatArray) = VectorIndex.quantize(v).let { (b, s) -> put(id, b, s) }

    @Test fun bytesKeepTheVectorClose() {
        val v = unit(768)
        val (bytes, scale) = VectorIndex.quantize(v)
        val back = FloatArray(v.size) { bytes[it] * scale }
        assertEquals(1f, dot(v, back), 0.001f)
    }

    @Test fun zeroVector() {
        val (bytes, scale) = VectorIndex.quantize(FloatArray(4))
        assertEquals(0f, scale)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun findsTheClosestFirst() {
        val index = VectorIndex(64)
        val vectors = (1L..3000L).associateWith { unit(64) } // past the first 1024 slots, so the arrays grow
        vectors.forEach { (id, v) -> index.add(id, v) }
        assertEquals(3000, index.size)
        val hits = index.search(vectors.getValue(1234L), 5)
        assertEquals(1234L, hits.first().id)
        assertEquals(1f, hits.first().score, 0.01f)
        assertTrue(hits.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test fun onlyAllowedPhotos() {
        val index = VectorIndex(16)
        val v = unit(16)
        index.add(1, v)
        index.add(2, unit(16))
        assertEquals(listOf(2L), index.search(v, 10) { it != 1L }.map { it.id })
    }

    @Test fun putReplaces() {
        val index = VectorIndex(16)
        val a = unit(16)
        val b = unit(16)
        index.add(1, a)
        index.add(1, b)
        assertEquals(1, index.size)
        assertEquals(1f, dot(b, index.vector(1)!!), 0.01f)
        assertFalse(index.contains(2))
    }
}
