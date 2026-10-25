package dev.hkgill.gillphotos.core

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueriesTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val today = LocalDate.of(2026, 10, 25) // a Sunday
    private val people = mapOf("sam" to 1L, "mum" to 2L, "aunty jas" to 3L)

    private fun parse(s: String, southern: Boolean = true) = Queries.parse(s, people, today, zone, southern)
    private fun ms(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun assertRange(q: Query, from: LocalDate, until: LocalDate) {
        assertEquals(ms(from.year, from.monthValue, from.dayOfMonth), q.from)
        assertEquals(ms(until.year, until.monthValue, until.dayOfMonth), q.until)
    }

    @Test fun plainWordsAreLeftForMeaning() {
        val q = parse("dog")
        assertEquals("dog", q.text)
        assertEquals(emptyList<Long>(), q.people)
        assertNull(q.from)
        assertNull(q.until)
    }

    @Test fun personPlaceAndSeasonInTheSouth() {
        val q = parse("Sam at the beach last summer")
        assertEquals(listOf(1L), q.people)
        assertEquals("beach", q.text)
        assertRange(q, LocalDate.of(2025, 12, 1), LocalDate.of(2026, 3, 1))
    }

    @Test fun lastSummerInTheNorth() =
        assertRange(parse("last summer", southern = false), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1))

    @Test fun thisSpringIsTheOneWeAreIn() =
        assertRange(parse("this spring"), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 1))

    @Test fun aSeasonOnItsOwnIsTheLatestStarted() =
        assertRange(parse("winter"), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 9, 1))

    @Test fun personAndYearLeaveNothing() {
        val q = parse("photos of mum from 2023")
        assertEquals(listOf(2L), q.people)
        assertEquals("", q.text)
        assertRange(q, LocalDate.of(2023, 1, 1), LocalDate.of(2024, 1, 1))
    }

    @Test fun lastMonth() {
        val q = parse("receipts from last month")
        assertEquals("receipts", q.text)
        assertRange(q, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))
    }

    @Test fun aMonthIsTheLatestThatHasStarted() {
        val q = parse("show me pictures of sunsets in March")
        assertEquals("sunsets", q.text)
        assertRange(q, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1))
        assertRange(parse("november"), LocalDate.of(2025, 11, 1), LocalDate.of(2025, 12, 1))
        assertRange(parse("october"), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))
    }

    @Test fun lastOctoberIsTheOneThatsOver() =
        assertRange(parse("last october"), LocalDate.of(2025, 10, 1), LocalDate.of(2025, 11, 1))

    @Test fun monthAndYear() = assertRange(parse("Mar 2024"), LocalDate.of(2024, 3, 1), LocalDate.of(2024, 4, 1))

    @Test fun days() {
        assertRange(parse("today"), LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26))
        assertRange(parse("yesterday"), LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25))
    }

    @Test fun weeksStartOnMonday() {
        assertRange(parse("this week"), LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26))
        assertRange(parse("last week"), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19))
    }

    @Test fun possessiveNames() {
        val q = parse("Sam's birthday")
        assertEquals(listOf(1L), q.people)
        assertEquals("birthday", q.text)
    }

    @Test fun namesOfTwoWords() = assertEquals(listOf(3L), parse("Aunty Jas cooking").people)

    @Test fun twoPeople() {
        val q = parse("pictures of Sam and Mum at the beach")
        assertEquals(listOf(1L, 2L), q.people)
        assertEquals("beach", q.text)
    }

    @Test fun placesInTheLibrary() {
        val places = mapOf("bondi beach" to "Bondi Beach", "sydney" to "Sydney")
        val q = Queries.parse("Sam at Bondi Beach last summer", people, today, zone, true, places)
        assertEquals(listOf("Bondi Beach"), q.places)
        assertEquals(listOf(1L), q.people)
        assertEquals("", q.text)
        val r = Queries.parse("sunsets in Sydney 2024", people, today, zone, true, places)
        assertEquals(listOf("Sydney"), r.places)
        assertEquals("sunsets", r.text)
        assertRange(r, LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1))
    }

    @Test fun theLeadIntoATimeGoesWithIt() = assertEquals("dog on the beach", parse("dog from yesterday on the beach").text)
}
