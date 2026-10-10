package dev.hkgill.gillspeak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The floating mic, as in the Claude Design "Bubble" component: a night circle holding the g with three lime sound
 * bars. While recording it becomes a night pill with a pulsing red dot, lime bars that follow the voice and the
 * timer; while transcribing, three pulsing lime dots; a failed request turns it amber with a retry arrow. The wide
 * bar shows the glyph in a small circle next to its label ("Hold to talk · tap to latch": the part after the dot is
 * dimmer). [inset] leaves room around the shape for its shadow.
 */
class BubbleView(context: Context, private val level: () -> Float) : View(context) {
    var state = MicController.State.IDLE
        set(v) { field = v; invalidate() }
    var retry = false
        set(v) { field = v; invalidate() }
    var bar = false
        set(v) { field = v; invalidateOutline(); invalidate() }
    /** Stretched into a pill while recording or transcribing, so it is obvious the mic is live. */
    var pill = false
        set(v) { field = v; invalidateOutline(); invalidate() }
    var label = ""
        set(v) { field = v; invalidate() }

    val inset = dp(8)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fonts = Fonts(context)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = fonts.semibold
    }
    private val shape = RectF()
    private var smoothed = 0f

    private val recording get() = state == MicController.State.RECORDING || state == MicController.State.LATCHED
    private val working get() = state == MicController.State.WORKING
    private val amber get() = retry && !recording && !working

    init {
        elevation = dp(6).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val h = (view.height - 2 * inset).toFloat()
                outline.setRoundRect(inset, inset, view.width - inset, view.height - inset, h / 2)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = invalidateOutline()

    override fun onDraw(canvas: Canvas) {
        shape.set(inset.toFloat(), inset.toFloat(), (width - inset).toFloat(), (height - inset).toFloat())
        val side = min(shape.width(), shape.height())
        val radius = shape.height() / 2
        fill.color = if (amber) AMBER_SOFT else NIGHT
        canvas.drawRoundRect(shape, radius, radius, fill)
        if (!amber) { // a hairline edge so it reads on dark wallpapers
            stroke.color = 0x14FFFFFF
            stroke.strokeWidth = dp(1).toFloat()
            canvas.drawRoundRect(shape, radius, radius, stroke)
        }
        val cy = shape.centerY()

        if (!pill && !bar) { // the round mark
            if (amber) drawRetry(canvas, shape.centerX(), cy, side * 0.4f)
            else Glyph.draw(canvas, shape.centerX(), cy, side * 2 / 3f)
            return
        }

        text.textSize = (side * 0.29f).coerceIn(dp(13).toFloat(), dp(18).toFloat())
        val gap = side * 0.22f
        val (main, rest) = label.split(" · ", limit = 2).let { it[0] to it.getOrNull(1)?.let { r -> " · $r" }.orEmpty() }
        val mainW = text.measureText(main)
        val labelW = if (label.isEmpty()) 0f else mainW + text.measureText(rest)
        val iconW = when {
            recording -> side * 1.15f
            working -> side * 0.62f
            amber -> side * 0.42f
            else -> side * 0.72f
        }
        // The round pill centres its content; the wide bar starts from the left like a text field.
        var x = if (bar) shape.left + side * 0.18f
        else shape.centerX() - (iconW + (if (labelW > 0) gap + labelW else 0f)) / 2
        when {
            recording -> drawListening(canvas, x, cy, iconW, side)
            working -> drawDots(canvas, x + iconW / 2, cy, side)
            amber -> drawRetry(canvas, x + iconW / 2, cy, iconW)
            else -> {
                fill.color = NIGHT_KEY
                canvas.drawCircle(x + iconW / 2, cy, iconW / 2, fill)
                Glyph.draw(canvas, x + iconW / 2, cy, iconW * 0.72f)
            }
        }
        x += iconW + gap
        if (labelW > 0) {
            val baseline = cy - (text.descent() + text.ascent()) / 2
            text.color = if (amber) AMBER_TEXT else INK
            text.typeface = if (amber) fonts.bold else fonts.semibold
            canvas.drawText(main, x, baseline, text)
            text.color = if (amber) AMBER_TEXT else DIM
            text.typeface = fonts.regular
            canvas.drawText(rest, x + mainW, baseline, text)
            text.typeface = fonts.semibold
        }
        if (recording || working) postInvalidateOnAnimation()
    }

    /** A gentle squeeze under the finger. */
    fun pressed(down: Boolean) {
        animate().scaleX(if (down) 0.92f else 1f).scaleY(if (down) 0.92f else 1f).setDuration(120).start()
    }

    /** A pulsing red dot and lime bars that follow the voice. */
    private fun drawListening(canvas: Canvas, left: Float, cy: Float, w: Float, side: Float) {
        val t = SystemClock.uptimeMillis() / 1000.0
        val dot = side * 0.09f
        fill.color = RECORDING
        fill.alpha = (255 * (0.72 + 0.28 * sin(t * 2 * PI / 1.2))).toInt()
        canvas.drawCircle(left + dot, cy, dot, fill)
        fill.alpha = 255
        val shapeOf = floatArrayOf(0.45f, 0.85f, 0.6f, 1f, 0.65f, 0.4f, 0.8f, 0.55f)
        val waveLeft = left + dot * 2 + side * 0.16f
        val step = (left + w - waveLeft) / shapeOf.size
        stroke.strokeWidth = step * 0.55f
        stroke.color = Look.LIME
        stroke.alpha = 255
        val maxH = side * 0.54f
        smoothed += (level() - smoothed) * 0.35f
        for (i in shapeOf.indices) {
            val wobble = 0.5f + 0.5f * sin(t * 7 + i * 1.3).toFloat()
            val h = maxOf(stroke.strokeWidth, maxH * shapeOf[i] * (0.3f + 0.7f * smoothed * (0.6f + 0.4f * wobble)))
            val x = waveLeft + step * (i + 0.5f)
            canvas.drawLine(x, cy - h / 2, x, cy + h / 2, stroke)
        }
    }

    private fun drawDots(canvas: Canvas, cx: Float, cy: Float, side: Float) {
        val r = side * 0.1f
        val t = SystemClock.uptimeMillis() / 1000.0
        fill.color = Look.LIME
        for (i in -1..1) {
            val p = 0.5 + 0.5 * sin(t * 2 * PI / 1.1 - (i + 1) * 1.03)
            fill.alpha = (64 + 191 * p).toInt()
            canvas.drawCircle(cx + i * r * 3.2f, cy, r * (0.8f + 0.2f * p.toFloat()), fill)
        }
        fill.alpha = 255
    }

    /** Lucide's rotate-ccw: an arc that runs most of the way round, with its arrowhead at the top left. */
    private fun drawRetry(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val s = size / 24f
        canvas.save()
        canvas.translate(cx - size / 2, cy - size / 2)
        canvas.scale(s, s)
        stroke.color = AMBER_TEXT
        stroke.strokeWidth = 2.75f
        canvas.drawArc(3f, 3f, 21f, 21f, 180f, -270f, false, stroke) // from the left, clockwise round to the top
        canvas.drawPath(Path().apply { moveTo(12f, 3f); cubicTo(9.5f, 3f, 7.1f, 4f, 5.26f, 5.74f); lineTo(3f, 8f) }, stroke)
        canvas.drawPath(Path().apply { moveTo(3f, 3f); lineTo(3f, 8f); lineTo(8f, 8f) }, stroke)
        canvas.restore()
    }

    private companion object {
        const val NIGHT = 0xFF_111318.toInt()
        const val NIGHT_KEY = 0xFF_2A2E37.toInt()
        const val INK = 0xFF_E9EAEE.toInt()
        const val DIM = 0xFF_A2A7B2.toInt()
        const val RECORDING = 0xFF_FF5A4E.toInt()
        const val AMBER_SOFT = 0xFF_FFF1DB.toInt()
        const val AMBER_TEXT = 0xFF_7A4A00.toInt()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
