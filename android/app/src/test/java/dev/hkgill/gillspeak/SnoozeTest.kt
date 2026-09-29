package dev.hkgill.gillspeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnoozeTest {
    private val now = 1_000_000_000L

    @Test fun theChosenLengthDecidesWhenItEnds() {
        assertEquals(now + 10 * 60_000L, Snooze.until(now, 10))
        assertEquals(now + 4 * 60 * 60_000L, Snooze.until(now, 240))
    }

    @Test fun activeUntilItEnds() {
        val until = Snooze.until(now, 30)
        assertTrue(Snooze.isActive(now, until))
        assertTrue(Snooze.isActive(until - 1, until))
        assertFalse(Snooze.isActive(until, until))
        assertFalse(Snooze.isActive(until + 1, until))
    }

    @Test fun endedOrNeverStartedIsNotActive() {
        assertFalse(Snooze.isActive(now, 0L)) // endSnooze() removes the value, which reads back as 0
    }

    @Test fun unknownStoredLengthsFallBackToTheDefault() {
        for (m in Snooze.MINUTES) assertEquals(m, Snooze.minutesOrDefault(m))
        assertEquals(Snooze.DEFAULT_MINUTES, Snooze.minutesOrDefault(0))
        assertEquals(Snooze.DEFAULT_MINUTES, Snooze.minutesOrDefault(7))
        assertEquals(Snooze.DEFAULT_MINUTES, Snooze.minutesOrDefault(-10))
    }

    @Test fun overTheTargetMeansWithinTheRadius() {
        assertTrue(Snooze.over(500f, 2000f, 500f, 2000f, 100f))
        assertTrue(Snooze.over(560f, 2080f, 500f, 2000f, 100f)) // exactly 100 away
        assertFalse(Snooze.over(561f, 2080f, 500f, 2000f, 100f))
        assertFalse(Snooze.over(900f, 600f, 500f, 2000f, 100f))
    }

    @Test fun labels() {
        assertEquals("10 min", Snooze.label(10))
        assertEquals("30 min", Snooze.label(30))
        assertEquals("1 hour", Snooze.label(60))
        assertEquals("4 hours", Snooze.label(240))
    }
}
