package dev.hkgill.murmur

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The floating mic, drawn rather than built from an emoji: a soft rounded square (or a wide bar) showing a "G".
 * Recording it turns red and five bars follow your voice; while transcribing three dots pulse.
 * [inset] leaves room around the shape for its shadow.
 */
class BubbleView(context: Context, private val level: () -> Float) : View(context) {
    var state = MicController.State.IDLE
        set(v) { field = v; invalidate() }
    var retry = false
        set(v) { field = v; invalidate() }
    var bar = false
        set(v) { field = v; invalidate() }
    /** The square stretched into a pill while recording or transcribing, so it is obvious the mic is live. */
    var pill = false
        set(v) { field = v; invalidateOutline(); invalidate() }
    var label = ""
        set(v) { field = v; invalidate() }

    val inset = dp(8)

    private val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    // Slightly see-through, so the bubble sits on top of things instead of blocking them.
    private val surface = if (night) 0xE625262E.toInt() else 0xE6F3F6FE.toInt()
    private val stroke = if (night) 0x22FFFFFF else 0x14000000
    private val ink = if (night) 0xFFE6E6EB.toInt() else 0xFF3A3A44.toInt()
    private val accent = if (night) 0xFF7FA2FF.toInt() else 0xFF2F6BF0.toInt()
    private val red = 0xF2E5484D.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
    private val bars = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val shape = RectF()
    private var smoothed = 0f

    init {
        elevation = dp(3).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(inset, inset, view.width - inset, view.height - inset, radius())
            }
        }
    }

    private fun radius(): Float {
        val side = (min(width, height) - 2 * inset).toFloat()
        return when {
            pill -> side / 2
            bar -> dp(18).toFloat()
            else -> side * 0.3f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = invalidateOutline()

    override fun onDraw(canvas: Canvas) {
        shape.set(inset.toFloat(), inset.toFloat(), (width - inset).toFloat(), (height - inset).toFloat())
        val recording = state == MicController.State.RECORDING || state == MicController.State.LATCHED
        fill.color = if (recording) red else surface
        canvas.drawRoundRect(shape, radius(), radius(), fill)
        if (!recording) {
            border.color = stroke
            canvas.drawRoundRect(shape, radius(), radius(), border)
        }

        val side = min(shape.width(), shape.height())
        // In bar mode the icon sits at the left of the label; otherwise it is centred.
        val iconW = side * 0.46f
        val textSize = (shape.height() * 0.3f).coerceIn(dp(13).toFloat(), dp(20).toFloat())
        text.textSize = textSize
        val labelW = if ((bar || pill) && label.isNotEmpty()) text.measureText(label) + side * 0.25f else 0f
        val left = shape.centerX() - (iconW + labelW) / 2
        val cy = shape.centerY()

        when {
            state == MicController.State.WORKING -> drawDots(canvas, left + iconW / 2, cy, side)
            retry -> drawRetry(canvas, left + iconW / 2, cy, side)
            recording -> drawWave(canvas, left, cy, iconW, side)
            else -> drawG(canvas, left + iconW / 2, cy, side)
        }
        if (labelW > 0) {
            text.color = if (recording) Color.WHITE else ink
            canvas.drawText(label, left + iconW + side * 0.25f, cy - (text.descent() + text.ascent()) / 2, text)
        }
        // Quiet when idle, fully there while in use.
        alpha = if (recording || state == MicController.State.WORKING || retry) 1f else 0.82f
        if (recording || state == MicController.State.WORKING) postInvalidateOnAnimation()
    }

    /** A gentle squeeze under the finger. */
    fun pressed(down: Boolean) {
        animate().scaleX(if (down) 0.92f else 1f).scaleY(if (down) 0.92f else 1f).setDuration(120).start()
    }

    /** A geometric G: an arc open at the upper right, with its bar running in to the centre. */
    private fun drawG(canvas: Canvas, cx: Float, cy: Float, side: Float) {
        val r = side * 0.2f
        border.color = accent
        border.strokeWidth = side * 0.075f
        border.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 0f, 315f, false, border)
        canvas.drawLine(cx + r * 0.05f, cy, cx + r, cy, border)
        border.strokeWidth = dp(1).toFloat()
        border.strokeCap = Paint.Cap.BUTT
    }

    private fun drawWave(canvas: Canvas, left: Float, cy: Float, w: Float, side: Float) {
        val shapeOf = floatArrayOf(0.38f, 0.72f, 1f, 0.64f, 0.34f)
        val gap = w / shapeOf.size
        bars.strokeWidth = gap * 0.52f
        bars.color = Color.WHITE
        val maxH = side * 0.42f
        smoothed += (level() - smoothed) * 0.35f
        val t = SystemClock.uptimeMillis() / 1000.0
        for (i in shapeOf.indices) {
            val wobble = 0.5f + 0.5f * sin(t * 9 + i * 1.3).toFloat()
            val h = maxH * shapeOf[i] * (0.25f + 0.75f * smoothed * (0.6f + 0.4f * wobble))
            val x = left + gap * (i + 0.5f)
            canvas.drawLine(x, cy - h / 2, x, cy + h / 2, bars)
        }
    }

    private fun drawDots(canvas: Canvas, cx: Float, cy: Float, side: Float) {
        val r = side * 0.05f
        val t = SystemClock.uptimeMillis() / 1000.0
        for (i in -1..1) {
            fill.color = accent
            fill.alpha = (90 + 165 * (0.5 + 0.5 * sin(t * 2 * PI * 1.2 - (i + 1) * 0.9))).toInt()
            canvas.drawCircle(cx + i * r * 3.2f, cy, r, fill)
        }
        fill.alpha = 255
    }

    private fun drawRetry(canvas: Canvas, cx: Float, cy: Float, side: Float) {
        val r = side * 0.16f
        border.color = accent
        border.strokeWidth = side * 0.06f
        border.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -60f, 300f, false, border)
        // Arrow head at the end of the arc.
        fill.color = accent
        val a = Math.toRadians(-60.0)
        val hx = cx + r * kotlin.math.cos(a).toFloat()
        val hy = cy + r * kotlin.math.sin(a).toFloat()
        canvas.drawCircle(hx, hy, border.strokeWidth * 0.9f, fill)
        border.strokeWidth = dp(1).toFloat()
        border.strokeCap = Paint.Cap.BUTT
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
