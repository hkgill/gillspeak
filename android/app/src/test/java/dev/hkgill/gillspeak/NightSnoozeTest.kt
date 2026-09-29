package dev.hkgill.gillspeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class NightSnoozeTest {
    private val start = 22 * 60
    private val end = 7 * 60
    private fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 9, 29, h, m)

    @Test fun overnightWindowCrossesMidnight() {
        assertTrue(Snooze.inNight(LocalTime.of(22, 0), start, end))
        assertTrue(Snooze.inNight(LocalTime.of(23, 59), start, end))
        assertTrue(Snooze.inNight(LocalTime.of(0, 0), start, end))
        assertTrue(Snooze.inNight(LocalTime.of(6, 59), start, end))
        assertFalse(Snooze.inNight(LocalTime.of(7, 0), start, end))
        assertFalse(Snooze.inNight(LocalTime.of(12, 0), start, end))
        assertFalse(Snooze.inNight(LocalTime.of(21, 59), start, end))
    }

    @Test fun daytimeWindowWorksToo() {
        assertTrue(Snooze.inNight(LocalTime.of(13, 30), 13 * 60, 14 * 60))
        assertFalse(Snooze.inNight(LocalTime.of(14, 0), 13 * 60, 14 * 60))
        assertFalse(Snooze.inNight(LocalTime.of(12, 59), 13 * 60, 14 * 60))
    }

    @Test fun sameStartAndEndMeansNever() {
        assertFalse(Snooze.inNight(LocalTime.of(3, 0), 600, 600))
        assertNull(Snooze.nextNightChange(at(3), 600, 600))
    }

    @Test fun beforeMidnightEndsTomorrowMorning() {
        assertEquals(LocalDateTime.of(2026, 9, 30, 7, 0), Snooze.nightEnd(at(23, 15), start, end))
    }

    @Test fun afterMidnightEndsThisMorning() {
        assertEquals(LocalDateTime.of(2026, 9, 29, 7, 0), Snooze.nightEnd(at(2), start, end))
    }

    @Test fun daytimeHasNoNightEnd() {
        assertNull(Snooze.nightEnd(at(12), start, end))
    }

    @Test fun nextChangeIsTheNearestBoundary() {
        assertEquals(at(22), Snooze.nextNightChange(at(12), start, end)) // day: the night starts next
        assertEquals(LocalDateTime.of(2026, 9, 30, 7, 0), Snooze.nextNightChange(at(22), start, end)) // just started
        assertEquals(at(7), Snooze.nextNightChange(at(6, 59), start, end))
    }

    @Test fun clock() {
        assertEquals("22:00", Snooze.clock(start))
        assertEquals("07:00", Snooze.clock(end))
    }
}
