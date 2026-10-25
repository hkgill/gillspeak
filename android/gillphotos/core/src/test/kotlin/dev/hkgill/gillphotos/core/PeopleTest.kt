package dev.hkgill.gillphotos.core

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleTest {
    private val random = Random(11)
    private fun unit() = normalize(FloatArray(128) { random.nextFloat() * 2 - 1 })

    /** A face of the person whose centre is [c]: about 0.7 cosine to its centre, as SFace gives for one person. */
    private fun faceOf(c: FloatArray): FloatArray {
        val noise = unit()
        return normalize(FloatArray(128) { c[it] + 0.9f * noise[it] })
    }

    @Test fun findsThreePeopleAndDropsStrangers() {
        val centres = List(3) { unit() }
        val faces = ArrayList<People.Face>()
        var id = 0L
        val truth = HashMap<Long, Int>()
        for (round in 0 until 6) for ((p, c) in centres.withIndex()) {
            faces += People.Face(++id, faceOf(c))
            truth[id] = p
        }
        faces += People.Face(++id, unit()) // a stranger, seen once
        val grouping = People.group(faces)
        assertEquals(3, grouping.groups.size)
        for (g in grouping.groups) {
            assertEquals(6, g.size)
            assertEquals(1, g.map { truth[it] }.toSet().size)
        }
        assertTrue(grouping.groups.none { id in it })
    }

    @Test fun namedFacesStayAndPullInTheirLookalikes() {
        val sam = unit()
        val faces = listOf(People.Face(1, faceOf(sam), person = 42L), People.Face(2, faceOf(sam)), People.Face(3, faceOf(sam)))
        val grouping = People.group(faces)
        assertEquals(mapOf(1L to 42L, 2L to 42L, 3L to 42L), grouping.named)
        assertTrue(grouping.groups.isEmpty())
    }

    @Test fun twoNamedPeopleNeverMerge() {
        val c = unit()
        val faces = listOf(People.Face(1, faceOf(c), person = 1L), People.Face(2, faceOf(c), person = 2L))
        assertEquals(mapOf(1L to 1L, 2L to 2L), People.group(faces).named)
    }
}
