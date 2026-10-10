package dev.hkgill.gillspeak

/**
 * Mindful mode's timer: one budget of feed time shared by YouTube Shorts, Instagram Reels and TikTok, so hopping
 * between them doesn't start it again. Time counts only while a feed is on screen; five minutes away from all of them
 * is a real break and starts the budget again. At the limit the stop card offers "5 more minutes" once; leaving
 * pauses the feeds for 30 minutes. Plain arithmetic on wall-clock milliseconds, kept apart from the service so it
 * can be unit tested.
 */
object FeedClock {
    const val LIMIT_MS = 10 * 60_000L
    const val EXTRA_MS = 5 * 60_000L
    const val BREAK_MS = 5 * 60_000L
    const val PAUSE_MS = 30 * 60_000L

    /**
     * The longest gap between two looks that still counts as watching. The service looks every few seconds while a
     * feed is open, so a longer silence means it stopped watching (the service was restarted, the phone froze): the
     * feed is taken to have closed at the last look.
     */
    const val MAX_GAP_MS = 20_000L

    /**
     * [usedMs] of the budget is gone. [lastAt] is the last look while on a feed, or the moment the feed closed while
     * away from them. [extended]: "5 more minutes" has been used this session.
     */
    data class State(
        val usedMs: Long = 0, val lastAt: Long = 0, val onFeed: Boolean = false,
        val extended: Boolean = false, val pausedUntil: Long = 0,
    ) {
        val limitMs get() = LIMIT_MS + if (extended) EXTRA_MS else 0
        val overLimit get() = usedMs >= limitMs
        val leftMs get() = (limitMs - usedMs).coerceAtLeast(0)
        fun paused(now: Long) = now < pausedUntil
    }

    /** The service looked at the screen at [now], and a feed was on it or wasn't. */
    fun observe(s: State, now: Long, onFeed: Boolean): State = when {
        s.onFeed && now - s.lastAt > MAX_GAP_MS -> observe(s.copy(onFeed = false), now, onFeed)
        s.onFeed -> s.copy(usedMs = s.usedMs + (now - s.lastAt).coerceAtLeast(0), lastAt = now, onFeed = onFeed)
        !onFeed -> s
        now - s.lastAt >= BREAK_MS -> State(lastAt = now, onFeed = true, pausedUntil = s.pausedUntil)
        else -> s.copy(lastAt = now, onFeed = true)
    }

    /** "5 more minutes": once per session. */
    fun extend(s: State) = s.copy(extended = true)

    /** "Leave": the feeds pause for [PAUSE_MS], and the budget is whole again after it. */
    fun leave(s: State, now: Long) = State(lastAt = now, pausedUntil = now + PAUSE_MS)

    /** 185_000 → "3m 05s". */
    fun format(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return "%dm %02ds".format(s / 60, s % 60)
    }
}
