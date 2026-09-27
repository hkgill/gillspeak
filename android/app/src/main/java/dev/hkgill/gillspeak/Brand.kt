package dev.hkgill.gillspeak

import android.graphics.Path
import android.graphics.RectF

/** gillspeak's palette and mark: warm paper, near-black ink, one signal teal. */
object Brand {
    const val INK = 0xFF15171C.toInt()
    const val INK_2 = 0xFF4F5461.toInt() // secondary text on paper (≥ 6:1)
    const val PAPER = 0xFFF5F3EE.toInt()
    const val SURFACE = 0xFFFFFFFF.toInt()
    const val LINE = 0xFFE4E1DA.toInt()
    const val FIELD = 0xFFEEEBE4.toInt()
    const val TEAL = 0xFF14A394.toInt() // the mark's wave; fills and strokes
    const val TEAL_TEXT = 0xFF0B7A70.toInt() // teal text and buttons (≥ 4.5:1)
    const val TEAL_SOFT = 0xFFDDF1EE.toInt()
    const val TEAL_DEEP = 0xFF075A53.toInt() // text on TEAL_SOFT
    const val RECORDING = 0xFFE5484D.toInt()
    const val AMBER_SOFT = 0xFFFFF1E0.toInt()
    const val AMBER_TEXT = 0xFF8A4B00.toInt()

    // Dark (keyboard, dark mode)
    const val NIGHT = 0xFF0E0F12.toInt()
    const val NIGHT_SURFACE = 0xFF1C1E24.toInt()
    const val NIGHT_KEY = 0xFF262930.toInt()
    const val NIGHT_TEXT = 0xFFC9CCD3.toInt()

    /**
     * The mark's speech bubble (with its tail at the lower left), fitted into [box]. Designed on a 120-unit
     * square: the body spans 6..114 × 20..94 with 16-unit corners, the tail drops to y = 110.
     */
    fun bubblePath(box: RectF): Path {
        val s = box.width() / 120f
        fun x(v: Float) = box.left + v * s
        fun y(v: Float) = box.top + v * s
        val r = 16f * s
        return Path().apply {
            moveTo(x(22f), y(20f))
            lineTo(x(98f), y(20f))
            arcTo(x(114f) - 2 * r, y(20f), x(114f), y(20f) + 2 * r, -90f, 90f, false)
            lineTo(x(114f), y(78f))
            arcTo(x(114f) - 2 * r, y(94f) - 2 * r, x(114f), y(94f), 0f, 90f, false)
            lineTo(x(52f), y(94f))
            lineTo(x(32f), y(110f))
            lineTo(x(32f), y(94f))
            lineTo(x(22f), y(94f))
            arcTo(x(6f), y(94f) - 2 * r, x(6f) + 2 * r, y(94f), 90f, 90f, false)
            lineTo(x(6f), y(36f))
            arcTo(x(6f), y(20f), x(6f) + 2 * r, y(20f) + 2 * r, 180f, 90f, false)
            close()
        }
    }

    /** The g's tail, which reads as a small wave: from the stem down and round to the left. */
    fun tailPath(box: RectF): Path {
        val s = box.width() / 120f
        fun x(v: Float) = box.left + v * s
        fun y(v: Float) = box.top + v * s
        return Path().apply {
            moveTo(x(70f), y(40f))
            lineTo(x(70f), y(66f))
            cubicTo(x(70f), y(76f), x(63f), y(80f), x(55f), y(80f))
            cubicTo(x(50f), y(80f), x(47f), y(78f), x(45f), y(76f))
        }
    }
}
