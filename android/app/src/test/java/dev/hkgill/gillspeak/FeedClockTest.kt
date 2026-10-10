package dev.hkgill.gillspeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedClockTest {
    private val t0 = 1_000_000_000L
    private val sec = 1000L
    private val min = 60_000L
    private val TEN_MIN = FeedClock.DEFAULT_LIMIT_SEC

    /** Watches a feed from [from] for [ms], looking every 5 seconds as the service does, then looks once away. */
    private fun watch(s: FeedClock.State, from: Long, ms: Long, leave: Boolean = true): FeedClock.State {
        var state = FeedClock.observe(s, from, true)
        var t = from
        while (t + 5 * sec <= from + ms) { t += 5 * sec; state = FeedClock.observe(state, t, true) }
        return if (leave) FeedClock.observe(state, from + ms, false) else state
    }

    @Test fun countsOnlyTimeOnAFeed() {
        val s = watch(FeedClock.State(), t0, 3 * min)
        assertEquals(3 * min, s.usedMs)
        assertFalse(s.onFeed)
        // Away for two minutes: nothing counts.
        assertEquals(3 * min, FeedClock.observe(s, t0 + 5 * min, false).usedMs)
    }

    @Test fun oneTimerSharedAcrossApps() {
        // Shorts for 4 minutes, Reels 2 minutes later for 4, then TikTok straight after for 3: one total.
        var s = watch(FeedClock.State(), t0, 4 * min)
        s = watch(s, t0 + 6 * min, 4 * min, leave = false)
        s = watch(s, t0 + 10 * min, 3 * min) // switching apps is a look that's still on a feed
        assertEquals(11 * min, s.usedMs)
        assertTrue(s.overLimit(TEN_MIN))
    }

    @Test fun fiveMinutesAwayIsABreak() {
        val s = watch(FeedClock.State(), t0, 8 * min)
        assertEquals(8 * min, FeedClock.observe(s, t0 + 8 * min + 4 * min + 59 * sec, true).usedMs)
        val back = FeedClock.observe(s, t0 + 8 * min + 5 * min, true)
        assertEquals(0L, back.usedMs)
        assertTrue(back.onFeed)
    }

    @Test fun aLongSilenceCountsNothing() {
        // The service stopped looking mid-feed (restarted): the gap isn't counted, and a long one is a break.
        val s = watch(FeedClock.State(), t0, 2 * min, leave = false)
        assertEquals(2 * min, FeedClock.observe(s, t0 + 2 * min + 3 * min, true).usedMs)
        assertEquals(0L, FeedClock.observe(s, t0 + 2 * min + 6 * min, true).usedMs)
        assertEquals(2 * min, FeedClock.observe(s, t0 + 2 * min + 30 * sec, false).usedMs)
    }

    @Test fun theClockGoingBackAddsNothing() {
        val s = watch(FeedClock.State(), t0, 1 * min, leave = false)
        assertEquals(1 * min, FeedClock.observe(s, t0, true).usedMs)
    }

    @Test fun fiveMoreMinutesOnce() {
        var s = watch(FeedClock.State(), t0, 10 * min, leave = false)
        assertTrue(s.overLimit(TEN_MIN))
        s = FeedClock.extend(s)
        assertFalse(s.overLimit(TEN_MIN))
        assertEquals(5 * min, s.leftMs(TEN_MIN))
        s = watch(s, t0 + 10 * min, 5 * min, leave = false)
        assertTrue(s.overLimit(TEN_MIN))
        assertTrue(s.extended) // the stop card won't offer it again
    }

    @Test fun theExtensionLastsUntilABreak() {
        var s = FeedClock.extend(watch(FeedClock.State(), t0, 10 * min))
        s = FeedClock.observe(s, t0 + 12 * min, true)
        assertTrue(s.extended)
        s = FeedClock.observe(FeedClock.observe(s, t0 + 13 * min, false), t0 + 18 * min, true)
        assertFalse(s.extended)
        assertEquals(0L, s.usedMs)
    }

    @Test fun leavingPausesTheFeedsThenStartsAfresh() {
        val left = FeedClock.leave(watch(FeedClock.State(), t0, 10 * min, leave = false), t0 + 10 * min)
        assertTrue(left.paused(t0 + 10 * min))
        assertTrue(left.paused(t0 + 39 * min))
        assertFalse(left.paused(t0 + 40 * min))
        assertEquals(0L, left.usedMs)
        assertFalse(left.onFeed)
        val back = FeedClock.observe(left, t0 + 41 * min, true)
        assertEquals(0L, back.usedMs)
        assertFalse(back.overLimit(TEN_MIN))
    }

    @Test fun thePauseOutlastsABreak() {
        val left = FeedClock.leave(watch(FeedClock.State(), t0, 10 * min, leave = false), t0 + 10 * min)
        val back = FeedClock.observe(left, t0 + 20 * min, true) // a break by length, but still inside the pause
        assertTrue(back.paused(t0 + 20 * min))
    }

    @Test fun theBudgetIsTheUsersChoice() {
        val s = watch(FeedClock.State(), t0, 35 * sec, leave = false)
        assertTrue(s.overLimit(30))
        assertFalse(s.overLimit(60))
        assertEquals(25 * sec, s.leftMs(60))
        assertEquals(30 * sec + 5 * min, FeedClock.extend(s).limitMs(30))
    }

    @Test fun thirtySecondsIsTheShortestBudget() {
        assertEquals(30, FeedClock.LIMITS_SEC.min())
        assertEquals(30, FeedClock.limitOrDefault(30))
        assertEquals(FeedClock.DEFAULT_LIMIT_SEC, FeedClock.limitOrDefault(10)) // not a choice: back to the default
        assertEquals(FeedClock.DEFAULT_LIMIT_SEC, FeedClock.limitOrDefault(0))
        assertEquals("30 sec", FeedClock.limitLabel(30))
        assertEquals("10 min", FeedClock.limitLabel(600))
    }

    @Test fun formatsMinutesAndSeconds() {
        assertEquals("0m 00s", FeedClock.format(0))
        assertEquals("3m 05s", FeedClock.format(185_000))
        assertEquals("12m 00s", FeedClock.format(12 * min))
    }
}
