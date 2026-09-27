package dev.hkgill.gillspeak

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Colours shared by the keyboard and the bubble, following the system's light/dark setting. */
data class Palette(val bg: Int, val key: Int, val text: Int, val dim: Int, val idle: Int, val rec: Int, val busy: Int) {
    fun forState(state: MicController.State) = when (state) {
        MicController.State.IDLE -> idle
        MicController.State.RECORDING, MicController.State.LATCHED -> rec
        MicController.State.WORKING -> busy
    }

    companion object {
        fun of(context: Context): Palette {
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return if (night) {
                Palette(0xFF1B1B1F.toInt(), 0xFF2E2E34.toInt(), Color.WHITE, 0xFFA0A0AA.toInt(), 0xFF3D7BFD.toInt(), 0xFFE5484D.toInt(), 0xFFD9901A.toInt())
            } else {
                Palette(0xFFE8E8ED.toInt(), Color.WHITE, 0xFF111114.toInt(), 0xFF5C5C66.toInt(), 0xFF2F6BF0.toInt(), 0xFFD93036.toInt(), 0xFFC7800F.toInt())
            }
        }
    }
}

fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
    cornerRadius = radiusPx
    setColor(color)
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
