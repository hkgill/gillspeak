package dev.hkgill.gillphotos

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.TextView

/** gillspeak's settings look (soft paper or night, white or graphite cards, lime for the live state), trimmed. */
data class Look(
    val bg: Int, val card: Int, val ink: Int, val ink2: Int, val line: Int, val field: Int,
    val accT: Int, val accSoft: Int, val btn: Int, val onBtn: Int, val attSoft: Int, val attT: Int,
) {
    companion object {
        const val LIME = 0xFF_C8F25A.toInt()

        val LIGHT = Look(
            bg = 0xFF_F3F4F0.toInt(), card = 0xFF_FFFFFF.toInt(), ink = 0xFF_111318.toInt(), ink2 = 0xFF_535866.toInt(),
            line = 0xFF_DFE1DC.toInt(), field = 0xFF_E8EAE5.toInt(), accT = 0xFF_4A6600.toInt(),
            accSoft = 0xFF_EEF9D2.toInt(), btn = 0xFF_111318.toInt(), onBtn = 0xFF_F3F4F0.toInt(),
            attSoft = 0xFF_FFF1DB.toInt(), attT = 0xFF_7A4A00.toInt(),
        )
        val DARK = Look(
            bg = 0xFF_111318.toInt(), card = 0xFF_1B1E24.toInt(), ink = 0xFF_E9EAEE.toInt(), ink2 = 0xFF_A2A7B2.toInt(),
            line = 0xFF_2A2E37.toInt(), field = 0xFF_2A2E37.toInt(), accT = LIME, accSoft = 0xFF_2B3318.toInt(),
            btn = LIME, onBtn = 0xFF_111318.toInt(), attSoft = 0xFF_3A2D12.toInt(), attT = 0xFF_F5C26B.toInt(),
        )

        fun of(context: Context) =
            if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
    }
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
    cornerRadius = radiusPx
    setColor(color)
}

fun Context.label(text: String, look: Look, size: Float = 15f, bold: Boolean = false, dim: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(if (dim) look.ink2 else look.ink)
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

/** A pill button: filled for the main action, outlined in [Look.field] otherwise. 48 dp tall for touch. */
fun Context.pill(text: String, look: Look, main: Boolean = false, onClick: () -> Unit) = Button(this).apply {
    this.text = text
    isAllCaps = false
    textSize = 14f
    minHeight = dp(48)
    minimumHeight = dp(48)
    setPadding(dp(18), 0, dp(18), 0)
    gravity = Gravity.CENTER
    setTextColor(if (main) look.onBtn else look.ink)
    background = rounded(if (main) look.btn else look.field, dp(24).toFloat())
    stateListAnimator = null
    setOnClickListener { onClick() }
}
