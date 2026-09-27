package dev.hkgill.gillspeak

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** gillspeak's mark at any size: an ink speech bubble holding a paper g with a teal tail. */
class MarkView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Brand.INK }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val box = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val side = min(width, height).toFloat()
        box.set((width - side) / 2, (height - side) / 2, (width + side) / 2, (height + side) / 2)
        canvas.drawPath(Brand.bubblePath(box), fill)
        val s = side / 120f
        stroke.strokeWidth = 10f * s
        stroke.color = Brand.PAPER
        canvas.drawCircle(box.left + 56f * s, box.top + 52f * s, 14f * s, stroke)
        stroke.color = Brand.TEAL
        canvas.drawPath(Brand.tailPath(box), stroke)
    }
}
