package dev.hkgill.gillphotos.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FacesTest {
    private val template = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)

    private fun emptyOutputs(): MutableMap<String, FloatArray> = YuNet.OUTPUTS.associateWith { name ->
        val stride = name.substringAfter('_').toInt()
        val cells = (YuNet.SIZE / stride) * (YuNet.SIZE / stride)
        FloatArray(cells * when (name.substringBefore('_')) { "bbox" -> 4; "kps" -> 10; else -> 1 })
    }.toMutableMap()

    @Test fun decodesOneFace() {
        val out = emptyOutputs()
        // Stride 16, row 5, column 7: a face centred on that cell, 32 × 48 pixels.
        val i = 5 * (640 / 16) + 7
        out.getValue("cls_16")[i] = 0.9f
        out.getValue("obj_16")[i] = 1f
        out.getValue("bbox_16").apply { this[i * 4] = 0.5f; this[i * 4 + 1] = 0.5f; this[i * 4 + 2] = kotlin.math.ln(2f); this[i * 4 + 3] = kotlin.math.ln(3f) }
        out.getValue("kps_16").apply { this[i * 10] = 0.25f; this[i * 10 + 1] = 0.75f }
        val faces = YuNet.decode(out, minScore = 0.5f)
        assertEquals(1, faces.size)
        val f = faces[0]
        assertEquals(kotlin.math.sqrt(0.9f), f.score, 1e-5f)
        assertEquals((7.5f * 16) - 16f, f.x, 1e-3f)
        assertEquals((5.5f * 16) - 24f, f.y, 1e-3f)
        assertEquals(32f, f.w, 1e-3f)
        assertEquals(48f, f.h, 1e-3f)
        assertEquals(7.25f * 16, f.landmarks[0], 1e-3f)
        assertEquals(5.75f * 16, f.landmarks[1], 1e-3f)
    }

    @Test fun lowScoresAreDropped() {
        val out = emptyOutputs()
        out.getValue("cls_8")[0] = 0.3f
        out.getValue("obj_8")[0] = 0.3f
        assertEquals(0, YuNet.decode(out, minScore = 0.5f).size)
    }

    @Test fun overlappingBoxesKeepTheSurest() {
        val a = Face(0f, 0f, 100f, 100f, 0.8f, FloatArray(10))
        val b = Face(10f, 10f, 100f, 100f, 0.9f, FloatArray(10))
        val c = Face(300f, 300f, 50f, 50f, 0.7f, FloatArray(10))
        assertEquals(listOf(b, c), YuNet.suppress(listOf(a, b, c), 0.3f, 50))
    }

    @Test fun inputIsPlanarBgrInTheCorner() {
        val input = YuNet.input(intArrayOf(0xFF102030.toInt(), 0xFF405060.toInt()), 2, 1)
        val plane = 640 * 640
        assertArrayEquals(floatArrayOf(0x30.toFloat(), 0x60.toFloat(), 0f), floatArrayOf(input[0], input[1], input[2]), 0f)
        assertEquals(0x20.toFloat(), input[plane], 0f)
        assertEquals(0x10.toFloat(), input[2 * plane], 0f)
    }

    @Test fun templateLandmarksNeedNoMove() {
        val m = Align.transform(template)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), m, 1e-4f)
    }

    @Test fun aBiggerTurnedFaceIsBroughtBack() {
        // The template turned 30°, doubled and moved: the transform must undo all three.
        val angle = Math.toRadians(30.0)
        val (cos, sin) = kotlin.math.cos(angle).toFloat() to kotlin.math.sin(angle).toFloat()
        val moved = FloatArray(10) { 0f }
        for (i in 0 until 5) {
            val x = template[2 * i]
            val y = template[2 * i + 1]
            moved[2 * i] = 2 * (cos * x - sin * y) + 200
            moved[2 * i + 1] = 2 * (sin * x + cos * y) + 50
        }
        val (a, b, tx, ty) = Align.transform(moved)
        for (i in 0 until 5) {
            val x = moved[2 * i]
            val y = moved[2 * i + 1]
            assertEquals(template[2 * i], a * x - b * y + tx, 1e-3f)
            assertEquals(template[2 * i + 1], b * x + a * y + ty, 1e-3f)
        }
    }

    @Test fun warpWithNoMoveCopiesTheCorner() {
        val w = 150
        val h = 120
        val picture = IntArray(w * h) { 0xFF000000.toInt() or (it % w shl 16) or (it / w shl 8) }
        val face = Align.warp(picture, w, h, floatArrayOf(1f, 0f, 0f, 0f))
        for (y in 0 until Align.SIZE step 13) for (x in 0 until Align.SIZE step 17) {
            assertEquals(picture[y * w + x], face[y * Align.SIZE + x])
        }
    }

    @Test fun sfaceInputIsPlanarRgb() {
        val face = IntArray(Align.SIZE * Align.SIZE) { 0xFF112233.toInt() }
        val input = SFace.input(face)
        val plane = Align.SIZE * Align.SIZE
        assertEquals(0x11.toFloat(), input[0], 0f)
        assertEquals(0x22.toFloat(), input[plane], 0f)
        assertEquals(0x33.toFloat(), input[2 * plane], 0f)
    }
}
