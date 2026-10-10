package dev.hkgill.gillspeak

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Mindful mode's stop: a full-screen card over the feed. "Leave" goes home; "5 more minutes" is offered once per
 * session, and only after a 10-second wait, so it takes a moment's thought. Always night, whatever the phone's theme,
 * to cover the video underneath. It takes every touch, so the feed can't be scrolled behind it.
 */
class StopCard(context: Context, private val onLeave: () -> Unit, private val onMore: () -> Unit) : FrameLayout(context) {
    private val fonts = Fonts(context)
    private val title = text(26f, Look.LOGO_INK, fonts.bold)
    private val sub = text(16f, Look.DARK.ink2, fonts.regular)
    private val leave = button("Leave", filled = true)
    private val more = button("", filled = false)
    private var wait = 0
    private val tick = object : Runnable {
        override fun run() {
            wait--
            label()
            if (wait > 0) postDelayed(this, 1000)
        }
    }

    init {
        setBackgroundColor(0xF5_111318.toInt())
        isClickable = true // swallow touches meant for the feed
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(32), 0, context.dp(32), 0)
            addView(LogoView(context), LinearLayout.LayoutParams(context.dp(64), context.dp(64)).apply { bottomMargin = context.dp(28) })
            addView(title)
            addView(sub, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(10); bottomMargin = context.dp(36) })
            addView(leave, LinearLayout.LayoutParams(MATCH, context.dp(56)))
            addView(more, LinearLayout.LayoutParams(MATCH, context.dp(56)).apply { topMargin = context.dp(12) })
        }, LayoutParams(MATCH, WRAP, Gravity.CENTER))
        leave.setOnClickListener { onLeave() }
        more.setOnClickListener { if (wait <= 0) onMore() }
    }

    /** Shows [heading] and [detail]; [offerMore] adds "5 more minutes", usable after the wait. */
    fun show(heading: String, detail: String, offerMore: Boolean) {
        title.text = heading
        sub.text = detail
        more.visibility = if (offerMore) View.VISIBLE else View.GONE
        removeCallbacks(tick)
        if (offerMore) {
            wait = WAIT_SEC
            postDelayed(tick, 1000)
        }
        label()
        announceForAccessibility("$heading. $detail")
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun label() {
        more.text = if (wait > 0) "5 more minutes ($wait)" else "5 more minutes"
        more.alpha = if (wait > 0) 0.45f else 1f
        more.isEnabled = wait <= 0
    }

    private fun text(size: Float, color: Int, face: android.graphics.Typeface) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        typeface = face
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.15f)
    }

    private fun button(label: String, filled: Boolean) = text(17f, if (filled) Look.LOGO_BG else Look.LOGO_INK, fonts.bold).apply {
        text = label
        background = if (filled) rounded(Look.LIME, context.dp(999).toFloat()) else
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = context.dp(999).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(context.dp(2), Look.DARK.line2)
            }
        isClickable = true
        isFocusable = true
    }

    private companion object {
        const val WAIT_SEC = 10
        const val MATCH = LayoutParams.MATCH_PARENT
        const val WRAP = LayoutParams.WRAP_CONTENT
    }
}
