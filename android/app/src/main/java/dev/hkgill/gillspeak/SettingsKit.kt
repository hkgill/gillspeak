package dev.hkgill.gillspeak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Switch

/**
 * The settings screen's look, from the Claude Design "Settings" canvas: soft paper or night, white or graphite
 * cards, and lime for the live state and the main action. The bubble, keyboard and voice screen keep [Brand].
 */
data class Look(
    val bg: Int, val card: Int, val ink: Int, val ink2: Int, val line: Int, val line2: Int, val field: Int, val off: Int,
    val accT: Int, val accSoft: Int, val btn: Int, val onBtn: Int, val attSoft: Int, val attT: Int,
    val done: Int, val onDone: Int, val swOn: Int, val swThumb: Int,
) {
    companion object {
        const val LIME = 0xFF_C8F25A.toInt()
        const val LOGO_BG = 0xFF_111318.toInt()
        const val LOGO_INK = 0xFF_E9EAEE.toInt()

        val LIGHT = Look(
            bg = 0xFF_F3F4F0.toInt(), card = 0xFF_FFFFFF.toInt(), ink = 0xFF_111318.toInt(), ink2 = 0xFF_535866.toInt(),
            line = 0xFF_DFE1DC.toInt(), line2 = 0xFF_C9CCC5.toInt(), field = 0xFF_E8EAE5.toInt(), off = 0xFF_C9CCC5.toInt(),
            accT = 0xFF_4A6600.toInt(), accSoft = 0xFF_EEF9D2.toInt(), btn = 0xFF_111318.toInt(), onBtn = 0xFF_F3F4F0.toInt(),
            attSoft = 0xFF_FFF1DB.toInt(), attT = 0xFF_7A4A00.toInt(), done = 0xFF_111318.toInt(), onDone = LIME,
            swOn = 0xFF_111318.toInt(), swThumb = LIME,
        )
        val DARK = Look(
            bg = 0xFF_111318.toInt(), card = 0xFF_1B1E24.toInt(), ink = 0xFF_E9EAEE.toInt(), ink2 = 0xFF_A2A7B2.toInt(),
            line = 0xFF_2A2E37.toInt(), line2 = 0xFF_3A3F4A.toInt(), field = 0xFF_2A2E37.toInt(), off = 0xFF_3A3F4A.toInt(),
            accT = LIME, accSoft = 0xFF_2B3318.toInt(), btn = LIME, onBtn = 0xFF_111318.toInt(),
            attSoft = 0xFF_3A2D12.toInt(), attT = 0xFF_F5C26B.toInt(), done = LIME, onDone = 0xFF_111318.toInt(),
            swOn = LIME, swThumb = 0xFF_111318.toInt(),
        )
    }
}

/** Figtree (bundled, SIL Open Font License) at the four weights the design uses. */
class Fonts(private val context: Context) {
    private fun weight(w: Int): Typeface = runCatching {
        Typeface.Builder(context.assets, "fonts/Figtree.ttf").setFontVariationSettings("'wght' $w").build()
    }.getOrDefault(if (w >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)

    val regular = weight(400)
    val semibold = weight(600)
    val bold = weight(700)
    val heavy = weight(800)
}

/** The design's 52 × 32 switch: [Look.swOn] track with a lime (or night) thumb when on. */
class Toggle(context: Context, private val look: Look) : View(context) {
    var checked = false
        set(v) {
            if (field == v) return
            field = v
            if (isAttachedToWindow) animateTo(if (v) 1f else 0f) else { pos = if (v) 1f else 0f; invalidate() }
        }
    private var pos = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO // the row it sits in is the switch
    }

    private fun animateTo(target: Float) = ValueAnimator.ofFloat(pos, target).apply {
        duration = 150
        addUpdateListener { pos = it.animatedValue as Float; invalidate() }
        start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(context.dp(52), context.dp(32))

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        box.set(0f, 0f, width.toFloat(), h)
        paint.color = blend(look.off, look.swOn, pos)
        canvas.drawRoundRect(box, h / 2, h / 2, paint)
        val r = context.dp(12).toFloat()
        val left = context.dp(4) + pos * (width - 2 * context.dp(4) - 2 * r)
        paint.color = if (pos > 0.5f) look.swThumb else look.card
        canvas.drawCircle(left + r, h / 2, r, paint)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = ((a shr shift and 0xFF) * (1 - t) + (b shr shift and 0xFF) * t).toInt() shl shift
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}

/** Marks a row as a switch for TalkBack, so it reads "on" or "off" and offers to toggle. */
fun View.actAsSwitch(isOn: () -> Boolean) {
    accessibilityDelegate = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = Switch::class.java.name
            info.isCheckable = true
            info.isChecked = isOn()
        }
    }
}

/** The design's line icons (24-unit Lucide-style strokes): chevrons, back arrow, check, close. */
class IconView(context: Context, private val kind: Kind, var color: Int, private val stroke: Float = 2.75f) : View(context) {
    enum class Kind { RIGHT, DOWN, BACK, CHECK, CLOSE }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height) / 24f
        canvas.save()
        canvas.translate((width - 24 * s) / 2, (height - 24 * s) / 2)
        canvas.scale(s, s)
        paint.color = color
        paint.strokeWidth = stroke
        val p = Path()
        when (kind) {
            Kind.RIGHT -> { p.moveTo(9f, 18f); p.lineTo(15f, 12f); p.lineTo(9f, 6f) }
            Kind.DOWN -> { p.moveTo(6f, 9f); p.lineTo(12f, 15f); p.lineTo(18f, 9f) }
            Kind.BACK -> { p.moveTo(12f, 19f); p.lineTo(5f, 12f); p.lineTo(12f, 5f); p.moveTo(19f, 12f); p.lineTo(5f, 12f) }
            Kind.CHECK -> { p.moveTo(20f, 6f); p.lineTo(9f, 17f); p.lineTo(4f, 12f) }
            Kind.CLOSE -> { p.moveTo(18f, 6f); p.lineTo(6f, 18f); p.moveTo(6f, 6f); p.lineTo(18f, 18f) }
        }
        canvas.drawPath(p, paint)
        canvas.restore()
    }
}

/** The settings header's app tile: a night square holding a round g whose middle is three lime sound bars. */
class LogoView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Look.LOGO_BG }
    private val box = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height).toFloat()
        box.set(0f, 0f, side, side)
        canvas.drawRoundRect(box, side * 14 / 44, side * 14 / 44, fill)
        // The glyph is drawn on a 120-unit square, 32/44 of the tile, centred.
        val g = side * 32 / 44
        canvas.save()
        canvas.translate((side - g) / 2, (side - g) / 2)
        canvas.scale(g / 120f, g / 120f)
        paint.color = Look.LOGO_INK
        paint.strokeWidth = 11f
        canvas.drawCircle(54f, 52f, 28f, paint)
        canvas.drawPath(Path().apply {
            moveTo(82f, 26f); lineTo(82f, 84f)
            cubicTo(82f, 102f, 68f, 108f, 56f, 108f)
            cubicTo(47f, 108f, 41f, 105f, 36f, 100f)
        }, paint)
        paint.color = Look.LIME
        paint.strokeWidth = 9f
        canvas.drawLine(42f, 46f, 42f, 58f, paint)
        canvas.drawLine(54f, 38f, 54f, 66f, paint)
        canvas.drawLine(66f, 46f, 66f, 58f, paint)
        canvas.restore()
    }
}
