package dev.hkgill.gillspeak

import android.content.Context
import android.graphics.drawable.GradientDrawable

/** The gillspeak keyboard's colours: key surfaces, text, and the mic area for each state. */
data class Palette(val bg: Int, val key: Int, val text: Int, val dim: Int, val idle: Int, val rec: Int, val busy: Int) {
    fun forState(state: MicController.State) = when (state) {
        MicController.State.IDLE -> idle
        MicController.State.RECORDING, MicController.State.LATCHED -> rec
        MicController.State.WORKING -> busy
    }

    companion object {
        fun of(context: Context): Palette {
            // The keyboard is always gillspeak's dark look, in light and dark mode alike.
            return Palette(Brand.INK, Brand.NIGHT_KEY, Brand.NIGHT_TEXT, 0xFFA9ADB6.toInt(), 0xFF20232A.toInt(), 0xFF3A1E21.toInt(), 0xFF17302C.toInt())
        }
    }
}

fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
    cornerRadius = radiusPx
    setColor(color)
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
