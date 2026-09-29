package dev.hkgill.gillspeak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The drop target that appears at the bottom of the screen while the idle bubble is dragged: a dark circle with
 * "z z" and a label under it. It grows and turns teal ([armed]) when the bubble is close enough to snooze.
 */
class SnoozeTargetView(context: Context) : View(context) {
    var armed = false
        set(v) {
            if (field == v) return
            field = v
            animate().scaleX(if (v) 1.15f else 1f).scaleY(if (v) 1.15f else 1f).setDuration(120).start()
            invalidate()
        }
    var label = ""
        set(v) { field = v; invalidate() }

    /** Circle radius; the window is sized around it with room for the label and the armed growth. */
    val radius = context.dp(30).toFloat()
    val windowWidth = context.dp(160)
    val windowHeight = context.dp(120)
    /** The circle's centre inside this window. */
    val centreX get() = windowWidth / 2f
    val centreY get() = radius + context.dp(12)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        textSize = context.dp(13).toFloat()
        setShadowLayer(context.dp(3).toFloat(), 0f, 0f, 0xAA000000.toInt())
    }

    init {
        // Scale around the circle, not the middle of the window.
        pivotX = centreX
        pivotY = centreY
    }

    override fun onDraw(canvas: Canvas) {
        fill.color = if (armed) Brand.TEAL else Brand.INK
        fill.alpha = if (armed) 255 else 230
        canvas.drawCircle(centreX, centreY, radius, fill)
        glyph.color = Brand.PAPER
        glyph.textSize = radius * 0.62f
        canvas.drawText("z", centreX - radius * 0.2f, centreY + radius * 0.3f, glyph)
        glyph.textSize = radius * 0.42f
        canvas.drawText("z", centreX + radius * 0.25f, centreY - radius * 0.05f, glyph)
        caption.color = Brand.PAPER
        canvas.drawText(label, centreX, centreY + radius + context.dp(22), caption)
    }
}
