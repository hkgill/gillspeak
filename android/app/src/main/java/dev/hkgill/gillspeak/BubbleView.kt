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
 * The floating mic: gillspeak's mark, a speech bubble holding a g, drawn rather than built from an image. While
 * recording or transcribing it becomes a dark pill (a red dot, a live wave and the timer, or pulsing dots); a
 * failed request turns it amber. The wide bar shows the small mark next to a label. [inset] leaves room around
 * the shape for its shadow.
 */
class BubbleView(context: Context, private val level: () -> Float) : View(context) {
    var state = MicController.State.IDLE
        set(v) { field = v; invalidateOutline(); invalidate() }
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
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val shape = RectF()
    private val markBox = RectF()
    private var smoothed = 0f

    private val recording get() = state == MicController.State.RECORDING || state == MicController.State.LATCHED
    private val working get() = state == MicController.State.WORKING
    /** The bare speech-bubble mark (not a pill or a bar). */
    private val markShape get() = !pill && !bar

    init {
        elevation = dp(4).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (markShape) {
                    // The shadow follows the bubble, tail included.
                    outline.setPath(Brand.bubblePath(squareIn(view.width, view.height)))
                } else {
                    val side = (min(view.width, view.height) - 2 * inset).toFloat()
                    val r = if (bar) dp(18).toFloat() else side / 2
                    outline.setRoundRect(inset, inset, view.width - inset, view.height - inset, r)
                }
            }
        }
    }

    private fun squareIn(w: Int, h: Int): RectF {
        val side = (min(w, h) - 2 * inset).toFloat()
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        return RectF(left, top, left + side, top + side)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = invalidateOutline()

    override fun onDraw(canvas: Canvas) {
        shape.set(inset.toFloat(), inset.toFloat(), (width - inset).toFloat(), (height - inset).toFloat())
        val side = min(shape.width(), shape.height())

        if (markShape) {
            val box = squareIn(width, height)
            fill.color = if (retry) Brand.AMBER_SOFT else Brand.INK
            canvas.drawPath(Brand.bubblePath(box), fill)
            if (retry) drawRetry(canvas, box.centerX(), box.top + box.height() * 0.475f, box.width(), Brand.AMBER_TEXT)
            else drawG(canvas, box)
            alpha = if (retry) 1f else 0.92f
            return
        }

        // A pill (while in use) or the wide bar.
        val radius = if (bar) dp(18).toFloat() else side / 2
        fill.color = if (retry && !recording && !working) Brand.AMBER_SOFT else Brand.INK
        canvas.drawRoundRect(shape, radius, radius, fill)

        text.textSize = (side * 0.3f).coerceIn(dp(13).toFloat(), dp(18).toFloat())
        text.color = if (retry && !recording && !working) Brand.AMBER_TEXT else Brand.PAPER
        val gap = side * 0.22f
        val cy = shape.centerY()
        val iconW = when {
            recording -> side * 1.05f
            working -> side * 0.5f
            else -> side * 0.62f
        }
        val labelW = if (label.isNotEmpty()) text.measureText(label) else 0f
        var x = shape.centerX() - (iconW + (if (labelW > 0) gap + labelW else 0f)) / 2
        when {
            recording -> drawListening(canvas, x, cy, iconW, side)
            working -> drawDots(canvas, x + iconW / 2, cy, side)
            retry -> drawRetry(canvas, x + iconW / 2, cy, side * 1.2f, Brand.AMBER_TEXT)
            else -> {
                val m = iconW
                markBox.set(x, cy - m / 2 - m * 0.04f, x + m, cy + m / 2 - m * 0.04f)
                fill.color = Brand.PAPER
                canvas.drawPath(Brand.bubblePath(markBox), fill)
                drawG(canvas, markBox, ink = Brand.INK)
            }
        }
        x += iconW + gap
        if (labelW > 0) canvas.drawText(label, x, cy - (text.descent() + text.ascent()) / 2, text)

        alpha = 1f
        if (recording || working) postInvalidateOnAnimation()
    }

    /** A gentle squeeze under the finger. */
    fun pressed(down: Boolean) {
        animate().scaleX(if (down) 0.92f else 1f).scaleY(if (down) 0.92f else 1f).setDuration(120).start()
    }

    /** The g inside the bubble: a ring in [ink] and a teal tail that curls like a wave. */
    private fun drawG(canvas: Canvas, box: RectF, ink: Int = Brand.PAPER) {
        val s = box.width() / 120f
        stroke.strokeWidth = 9.5f * s
        stroke.color = ink
        canvas.drawCircle(box.left + 56f * s, box.top + 52f * s, 14f * s, stroke)
        stroke.color = Brand.TEAL
        canvas.drawPath(Brand.tailPath(box), stroke)
    }

    /** A red dot and a teal wave that follows the voice. */
    private fun drawListening(canvas: Canvas, left: Float, cy: Float, w: Float, side: Float) {
        val dot = side * 0.09f
        fill.color = Brand.RECORDING
        canvas.drawCircle(left + dot, cy, dot, fill)
        val shapeOf = floatArrayOf(0.35f, 0.7f, 0.5f, 1f, 0.6f, 0.4f, 0.75f)
        val waveLeft = left + dot * 2 + side * 0.14f
        val step = (left + w - waveLeft) / shapeOf.size
        stroke.strokeWidth = step * 0.5f
        stroke.color = Brand.TEAL
        val maxH = side * 0.5f
        smoothed += (level() - smoothed) * 0.35f
        val t = SystemClock.uptimeMillis() / 1000.0
        for (i in shapeOf.indices) {
            val wobble = 0.5f + 0.5f * sin(t * 9 + i * 1.3).toFloat()
            val h = maxH * shapeOf[i] * (0.25f + 0.75f * smoothed * (0.6f + 0.4f * wobble))
            val x = waveLeft + step * (i + 0.5f)
            canvas.drawLine(x, cy - h / 2, x, cy + h / 2, stroke)
        }
    }

    private fun drawDots(canvas: Canvas, cx: Float, cy: Float, side: Float) {
        val r = side * 0.07f
        val t = SystemClock.uptimeMillis() / 1000.0
        fill.color = Brand.TEAL
        for (i in -1..1) {
            fill.alpha = (70 + 185 * (0.5 + 0.5 * sin(t * 2 * PI * 1.2 - (i + 1) * 0.9))).toInt()
            canvas.drawCircle(cx + i * r * 3f, cy, r, fill)
        }
        fill.alpha = 255
    }

    /** A circular arrow. */
    private fun drawRetry(canvas: Canvas, cx: Float, cy: Float, side: Float, color: Int) {
        val r = side * 0.16f
        stroke.color = color
        stroke.strokeWidth = side * 0.06f
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, -60f, 300f, false, stroke)
        val a = Math.toRadians(-60.0)
        val hx = cx + r * kotlin.math.cos(a).toFloat()
        val hy = cy + r * kotlin.math.sin(a).toFloat()
        val head = Path().apply {
            moveTo(hx - r * 0.55f, hy - r * 0.1f)
            lineTo(hx, hy)
            lineTo(hx + r * 0.05f, hy - r * 0.55f)
        }
        canvas.drawPath(head, stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
