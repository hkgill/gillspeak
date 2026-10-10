package dev.hkgill.gillspeak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.RoundedCorner
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The listening overlay's lime wave: a line just inside the screen's rounded edge that runs out from the side
 * button both ways round, swells with the voice ([level]), and while [working] carries two lights round the edge.
 * The corners follow the display's real corner radius, which Android 12+ reports.
 */
class EdgeWaveView(context: Context, private val level: () -> Float) : View(context) {
    /** Where the side button is, as a fraction of the screen height down the right edge. */
    var originY = 0.4f
    var working = false
        set(v) { if (v && !field) workingSince = SystemClock.uptimeMillis(); field = v; invalidate() }

    private var reveal = 0f
    private var smoothed = 0f
    private var workingSince = 0L
    private var animator: ValueAnimator? = null

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val line = Paint(glow)
    private val core = Paint(glow)
    private val comet = Paint(glow)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val cometPath = Path()

    init {
        glow.color = 0x29_14A394
        line.color = Look.LIME
        core.color = 0xD9_DDF1EE.toInt()
        comet.color = 0xFF_E2FA9E.toInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Runs the wave out from the side button. */
    fun open() = animate(1f, 450, DecelerateInterpolator(2f), null)

    /** Pulls the wave back into the side button, then calls [done]. */
    fun close(done: () -> Unit) = animate(0f, 380, AccelerateInterpolator(1.6f), done)

    private fun animate(to: Float, ms: Long, interp: android.animation.TimeInterpolator, done: (() -> Unit)?) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(reveal, to).apply {
            duration = ms
            interpolator = interp
            addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
            if (done != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = done()
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    // ---- The rounded rectangle, walked clockwise from the top-left ----

    private var w = 0f
    private var h = 0f
    private var r = 0f
    private var top = 0f
    private var side = 0f
    private var arc = 0f
    private var perimeter = 0f
    private var s0 = 0f
    private val inset get() = dp(1.5f)
    private val pt = FloatArray(4) // x, y, inward normal x, y

    private fun measure() {
        w = width.toFloat()
        h = height.toFloat()
        val corner = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius?.toFloat()
        } else null
        r = ((corner?.takeIf { it > 0 } ?: dp(36f)) - inset).coerceIn(dp(8f), minOf(w, h) / 3)
        top = w - 2 * inset - 2 * r
        side = h - 2 * inset - 2 * r
        arc = (PI * r / 2).toFloat()
        perimeter = 2 * top + 2 * side + 4 * arc
        s0 = top + arc + (originY * h - (inset + r)).coerceIn(0f, side)
    }

    private fun corner(cx: Float, cy: Float, th: Double) {
        val c = cos(th).toFloat()
        val s = sin(th).toFloat()
        pt[0] = cx + r * c; pt[1] = cy + r * s; pt[2] = -c; pt[3] = -s
    }

    private fun point(at: Float) {
        var s = ((at % perimeter) + perimeter) % perimeter
        val x0 = inset; val y0 = inset; val x1 = w - inset; val y1 = h - inset
        fun straight(x: Float, y: Float, nx: Float, ny: Float) { pt[0] = x; pt[1] = y; pt[2] = nx; pt[3] = ny }
        if (s < top) return straight(x0 + r + s, y0, 0f, 1f); s -= top
        if (s < arc) return corner(x1 - r, y0 + r, -PI / 2 + s / r); s -= arc
        if (s < side) return straight(x1, y0 + r + s, -1f, 0f); s -= side
        if (s < arc) return corner(x1 - r, y1 - r, (s / r).toDouble()); s -= arc
        if (s < top) return straight(x1 - r - s, y1, 0f, -1f); s -= top
        if (s < arc) return corner(x0 + r, y1 - r, PI / 2 + s / r); s -= arc
        if (s < side) return straight(x0, y1 - r - s, 1f, 0f); s -= side
        corner(x0 + r, y0 + r, PI + s / r)
    }

    /** Traces the wave from [from] to [to], measured along the edge from the side button. */
    private fun trace(into: Path, from: Float, to: Float, t: Long, amp: Float, half: Float) {
        into.rewind()
        val step = dp(3f)
        var u = from
        var first = true
        while (u <= to + 0.01f) {
            val s = s0 + u
            point(s)
            val d = abs(u)
            val sd = s / resources.displayMetrics.density // the wave's shape is the same at any density
            val wave = (0.55f + 0.45f * sin(sd * 0.05f - t * 0.007f)) * (0.65f + 0.35f * sin(sd * 0.017f + t * 0.0021f))
            val near = 1 + 0.9f * exp(-d / dp(90f))
            val front = if (reveal < 0.999f) dp(8f) * exp(-((d - half) * (d - half)) / (2 * dp(22f) * dp(22f))) else 0f
            val off = dp(2.5f) + amp * wave * near + front
            val x = pt[0] + pt[2] * off
            val y = pt[1] + pt[3] * off
            if (first) into.moveTo(x, y) else into.lineTo(x, y)
            first = false
            u += step
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (reveal <= 0.002f || width == 0) return
        if (w != width.toFloat() || h != height.toFloat() || perimeter == 0f) measure()
        val t = SystemClock.uptimeMillis()
        smoothed += (level() - smoothed) * 0.3f

        // A soft glow where the side button is, brighter as you talk.
        point(s0)
        val gr = dp(140f)
        val a = (0.26f * (0.7f + 0.7f * smoothed) * reveal * 255).toInt().coerceIn(0, 255)
        fill.shader = RadialGradient(pt[0], pt[1], gr, (a shl 24) or 0xC8F25A, 0x0014A394, Shader.TileMode.CLAMP)
        canvas.drawCircle(pt[0], pt[1], gr, fill)

        val half = reveal * perimeter / 2
        val amp = dp(1.5f) + dp(10f) * smoothed
        trace(path, -half, half, t, amp, half)
        if (reveal >= 0.999f) path.close()
        glow.strokeWidth = dp(22f)
        canvas.drawPath(path, glow)
        line.strokeWidth = dp(3f)
        line.setShadowLayer(dp(10f), 0f, 0f, Look.LIME)
        canvas.drawPath(path, line)
        core.strokeWidth = dp(1.2f)
        canvas.drawPath(path, core)

        // Working it out: two lights run round the edge from the side button, one each way.
        if (working) {
            val run = ((t - workingSince) * dp(0.85f) / 1f) % perimeter
            comet.strokeWidth = dp(4f)
            comet.setShadowLayer(dp(14f), 0f, 0f, Look.LIME)
            trace(cometPath, run - dp(90f), run, t, amp, half)
            canvas.drawPath(cometPath, comet)
            trace(cometPath, -run, -run + dp(90f), t, amp, half)
            canvas.drawPath(cometPath, comet)
        }
        postInvalidateOnAnimation()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
