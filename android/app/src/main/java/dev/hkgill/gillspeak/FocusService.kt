package dev.hkgill.gillspeak

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.text.format.DateFormat
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Mindful mode. A separate accessibility service from the bubble, receiving events only from
 * YouTube, Instagram and TikTok, so each service's description stays accurate. It spots their short-video feeds from
 * view ids ([Feeds]), times them with [FeedClock], and covers the feed with a [StopCard] at the user's limit and while
 * the feeds are paused. It never reads text or takes screenshots. Debug builds also log what it saw to files/mindful.log.
 *
 *     adb shell run-as dev.hkgill.gillspeak cat files/mindful.log
 */
class FocusService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var power: PowerManager
    private var feed: Feeds.Feed? = null
    private var since = 0L
    private var lastLook = 0L
    private val look = Runnable { look() }
    private val probed = HashSet<String>()
    private lateinit var wm: WindowManager
    private var card: StopCard? = null
    private var cardPaused: Boolean? = null // which card is showing: the pause, the limit, or none

    override fun onServiceConnected() {
        settings = Settings(this)
        power = getSystemService(PowerManager::class.java)
        wm = getSystemService(WindowManager::class.java)
        log("service on (${FeedClock.format(settings.feedClock.usedMs)} used)")
        main.post(look)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() !in Feeds.PACKAGES) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && settings.mindfulProbe) {
            log("probe: window ${event.packageName} ${event.className}")
        }
        // At most one look per second; the last event of a burst still gets one.
        main.removeCallbacks(look)
        main.postDelayed(look, (lastLook + LOOK_MS - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        hideCard()
        if (::settings.isInitialized) {
            update(System.currentTimeMillis(), null)
            log("service off")
        }
        super.onDestroy()
    }

    private fun look() {
        main.removeCallbacks(look)
        lastLook = SystemClock.uptimeMillis()
        val root = if (power.isInteractive) runCatching { rootInActiveWindow }.getOrNull() else null
        val pkg = root?.packageName?.toString()
        val seen = root?.let { r -> Feeds.detect(pkg) { id -> r.findAccessibilityNodeInfosByViewId(id).any { it.isVisibleToUser } } }
        if (root != null && pkg in Feeds.PACKAGES && settings.mindfulProbe) probe(root)
        update(System.currentTimeMillis(), seen)
        // While a feed is open, keep looking: a video can play for a minute without an event, and leaving to an app
        // that isn't watched sends none at all.
        if (seen != null) main.postDelayed(look, POLL_MS)
    }

    private fun update(now: Long, seen: Feeds.Feed?) {
        val before = settings.feedClock
        val limit = settings.mindfulLimitSec
        val after = FeedClock.observe(before, now, seen != null)
        if (after != before) settings.feedClock = after
        if (seen != feed) {
            feed?.let { log("off ${it.name} after ${FeedClock.format(now - since)}, ${FeedClock.format(after.usedMs)} used") }
            if (seen != null) {
                if (before.usedMs > 0 && after.usedMs == 0L) log("break: the timer starts again")
                log("on ${seen.name}, ${FeedClock.format(after.usedMs)} of ${FeedClock.format(after.limitMs(limit))} used")
            }
            feed = seen
            since = now
        }
        if (!before.overLimit(limit) && after.overLimit(limit)) log("limit reached in ${seen?.name}")
        when {
            seen != null && after.paused(now) -> showCard(paused = true, after, limit)
            seen != null && after.overLimit(limit) -> showCard(paused = false, after, limit)
            else -> hideCard()
        }
    }

    private fun showCard(paused: Boolean, state: FeedClock.State, limit: Int) {
        if (cardPaused == paused) return
        val view = card ?: StopCard(this, ::leave, ::more).also {
            wm.addView(it, WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ))
            card = it
        }
        if (paused) {
            view.show("Feeds are paused", "Shorts, Reels and TikTok are back at ${DateFormat.getTimeFormat(this).format(state.pausedUntil)}.", offerMore = false)
        } else {
            view.show(
                "That's your ${FeedClock.limitLabel(limit)}",
                "Shorts, Reels and TikTok share one timer. Leave now and they pause for 30 minutes.",
                offerMore = !state.extended,
            )
        }
        cardPaused = paused
        log(if (paused) "stop card: paused" else "stop card: limit")
    }

    private fun hideCard() {
        card?.let { runCatching { wm.removeView(it) } }
        card = null
        cardPaused = null
    }

    /** "Leave": home, and at the limit the feeds pause. Leaving the pause card leaves the pause as it was. */
    private fun leave() {
        if (cardPaused == false) settings.feedClock = FeedClock.leave(settings.feedClock, System.currentTimeMillis())
        log("left")
        hideCard()
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun more() {
        settings.feedClock = FeedClock.extend(settings.feedClock)
        log("5 more minutes")
        hideCard()
    }

    /** Debug builds: logs each view id seen in the watched apps once, to find the feeds' ids. Ids only, no text. */
    private fun probe(root: AccessibilityNodeInfo) {
        val fresh = ArrayList<String>()
        val queue = ArrayDeque(listOf(root))
        var count = 0
        while (queue.isNotEmpty() && count++ < 3000) {
            val node = queue.removeFirst()
            node.viewIdResourceName?.takeIf { node.isVisibleToUser && probed.add(it) }?.let(fresh::add)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        if (fresh.isNotEmpty()) log("probe: ${fresh.sorted().joinToString(" ")}")
    }

    private fun log(line: String) {
        Log.i(Dictation.TAG, "mindful: $line")
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        runCatching {
            val file = File(filesDir, "mindful.log")
            if (file.length() > MAX_LOG_BYTES) file.writeText(file.readText().takeLast((MAX_LOG_BYTES / 2).toInt()).substringAfter('\n'))
            val stamp = SimpleDateFormat("MMM d HH:mm:ss", Locale.US).format(Date())
            file.appendText("$stamp  $line\n")
        }
    }

    private companion object {
        const val LOOK_MS = 1000L
        const val POLL_MS = 5000L
        const val MAX_LOG_BYTES = 256 * 1024L
    }
}
