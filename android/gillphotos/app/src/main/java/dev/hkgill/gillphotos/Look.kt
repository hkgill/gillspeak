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

/** The design's line icons (24-unit, Lucide-style strokes), drawn in [color]. */
class Icon(context: Context, private val kind: Kind, var color: Int, private val stroke: Float = 2.4f) : android.view.View(context) {
    enum class Kind { LOCK, SEARCH, CHECK, BACK, PIN }

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val s = minOf(width, height) / 24f
        canvas.save()
        canvas.translate((width - 24 * s) / 2, (height - 24 * s) / 2)
        canvas.scale(s, s)
        paint.color = color
        paint.strokeWidth = stroke
        val p = android.graphics.Path()
        when (kind) {
            Kind.LOCK -> {
                p.addRoundRect(5f, 11f, 19f, 21f, 2f, 2f, android.graphics.Path.Direction.CW)
                p.moveTo(8f, 11f); p.lineTo(8f, 7f); p.cubicTo(8f, 1.7f, 16f, 1.7f, 16f, 7f); p.lineTo(16f, 11f)
            }
            Kind.SEARCH -> { p.addCircle(11f, 11f, 7f, android.graphics.Path.Direction.CW); p.moveTo(20f, 20f); p.lineTo(16.5f, 16.5f) }
            Kind.CHECK -> { p.moveTo(20f, 6f); p.lineTo(9f, 17f); p.lineTo(4f, 12f) }
            Kind.BACK -> { p.moveTo(12f, 19f); p.lineTo(5f, 12f); p.lineTo(12f, 5f); p.moveTo(19f, 12f); p.lineTo(5f, 12f) }
            Kind.PIN -> {
                p.moveTo(12f, 21f); p.cubicTo(12f, 21f, 5f, 14.5f, 5f, 9.5f); p.cubicTo(5f, 5.4f, 8.1f, 2.5f, 12f, 2.5f)
                p.cubicTo(15.9f, 2.5f, 19f, 5.4f, 19f, 9.5f); p.cubicTo(19f, 14.5f, 12f, 21f, 12f, 21f)
                p.addCircle(12f, 9.5f, 2.5f, android.graphics.Path.Direction.CW)
            }
        }
        canvas.drawPath(p, paint)
        canvas.restore()
    }
}
