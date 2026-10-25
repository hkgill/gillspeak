package dev.hkgill.gillphotos.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * What a search asks for: [text] to match by meaning, named [people], a time range [from, until) in epoch ms, and
 * [places] the photos were taken in.
 */
data class Query(val text: String, val people: List<Long>, val from: Long?, val until: Long?, val places: List<String> = emptyList())

/**
 * Reads a search like "Sam at the beach last summer" with rules: the people the user has named, one time
 * ("yesterday", "last week", "March", "2023", "last summer") and what's left, to match by meaning. Our code works out
 * the dates, as gillspeak's Commands does for timers. Seasons follow the hemisphere: summer is December to February
 * in Australia.
 */
object Queries {
    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    ).flatMapIndexed { i, m -> listOf(m to i + 1, m.take(3) to i + 1) }.toMap() + ("sept" to 9)

    // The month each season starts in, north and south. Every season is three months.
    private val NORTH = mapOf("spring" to 3, "summer" to 6, "autumn" to 9, "fall" to 9, "winter" to 12)
    private val SOUTH = mapOf("autumn" to 3, "fall" to 3, "winter" to 6, "spring" to 9, "summer" to 12)

    /** Words that only frame a search ("show me photos of …"): dropped from its ends. */
    private val FILLER = setOf(
        "show", "me", "find", "search", "for", "my", "our", "all", "the", "a", "an", "some",
        "photos", "photo", "pictures", "picture", "pics", "pic", "images", "image",
        "of", "from", "in", "on", "at", "with", "and", "taken", "during",
    )

    /** Words that lead into a person or a time ("with Sam", "from 2023"): dropped along with it. */
    private val LEADS = setOf("of", "from", "in", "on", "at", "with", "and", "taken", "during")

    private class Word(val raw: String) {
        val key = raw.lowercase().removeSuffix("'s").removeSuffix("’s")
        var used = false
    }

    private class Span(val length: Int, val start: LocalDate, val end: LocalDate)

    /**
     * [people] maps each named person's lower-case name to their id; [places] each lower-case place name in the
     * library to the name as stored.
     */
    fun parse(
        input: String, people: Map<String, Long>, today: LocalDate, zone: ZoneId, southern: Boolean,
        places: Map<String, String> = emptyMap(),
    ): Query {
        val words = input.split(Regex("\\s+")).map { it.trim(',', '.', '!', '?', ';', ':', '"', '(', ')') }
            .filter { it.isNotEmpty() }.map(::Word)

        val found = match(words, people)
        val foundPlaces = match(words, places)

        var span: Span? = null
        for (j in words.indices) {
            if (words[j].used) continue
            span = time(words.map { if (it.used) "" else it.key }, j, today, if (southern) SOUTH else NORTH) ?: continue
            take(words, j, span.length)
            break
        }

        val left = words.filter { !it.used }.toMutableList()
        while (left.isNotEmpty() && left.first().key in FILLER) left.removeAt(0)
        while (left.isNotEmpty() && left.last().key in FILLER) left.removeAt(left.size - 1)
        return Query(
            left.joinToString(" ") { it.raw },
            found.toList(),
            span?.start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            span?.end?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            foundPlaces.toList(),
        )
    }

    /** Finds [names] (lower case, any number of words; longest first) among the unread words, and marks them read. */
    private fun <T> match(words: List<Word>, names: Map<String, T>): LinkedHashSet<T> {
        val found = LinkedHashSet<T>()
        val split = names.entries.map { (name, v) -> name.split(' ').filter { it.isNotEmpty() } to v }
            .filter { it.first.isNotEmpty() }
            .sortedByDescending { it.first.size }
        var i = 0
        while (i < words.size) {
            val hit = split.firstOrNull { (parts, _) ->
                i + parts.size <= words.size && parts.indices.all { !words[i + it].used && words[i + it].key == parts[it] }
            }
            if (hit == null) { i++; continue }
            found += hit.second
            take(words, i, hit.first.size)
            i += hit.first.size
        }
        return found
    }

    /** Marks [length] words from [at] as read, with the little words that led into them ("with", "from"). */
    private fun take(words: List<Word>, at: Int, length: Int) {
        for (k in at until at + length) words[k].used = true
        var k = at - 1
        while (k >= 0 && !words[k].used && words[k].key in LEADS) words[k--].used = true
    }

    private fun year(word: String?) = word?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in 1900..2100 }

    private fun time(k: List<String>, i: Int, today: LocalDate, seasons: Map<String, Int>): Span? {
        val w = k[i]
        val next = k.getOrNull(i + 1)
        when (w) {
            "today" -> return Span(1, today, today.plusDays(1))
            "yesterday" -> return Span(1, today.minusDays(1), today)
            "this", "last" -> {
                val back = w == "last"
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val month = today.withDayOfMonth(1)
                val year = today.withDayOfYear(1)
                when (next) {
                    "week" -> return if (back) Span(2, monday.minusWeeks(1), monday) else Span(2, monday, monday.plusWeeks(1))
                    "month" -> return if (back) Span(2, month.minusMonths(1), month) else Span(2, month, month.plusMonths(1))
                    "year" -> return if (back) Span(2, year.minusYears(1), year) else Span(2, year, year.plusYears(1))
                }
                MONTHS[next]?.let { m ->
                    // "last March" is the latest one that's over; "this March" is this year's.
                    val y = if (!back || m < today.monthValue) today.year else today.year - 1
                    return monthSpan(2, y, m)
                }
                seasons[next]?.let { start ->
                    val all = (today.year - 2..today.year + 1).map { LocalDate.of(it, start, 1) }
                    val pick = if (back) all.last { it.plusMonths(3) <= today } else all.first { it.plusMonths(3) > today }
                    return Span(2, pick, pick.plusMonths(3))
                }
            }
        }
        year(w)?.let { return Span(1, LocalDate.of(it, 1, 1), LocalDate.of(it + 1, 1, 1)) }
        MONTHS[w]?.let { m ->
            year(next)?.let { return monthSpan(2, it, m) }
            // A month on its own is the latest one that has started.
            return monthSpan(1, if (m <= today.monthValue) today.year else today.year - 1, m)
        }
        seasons[w]?.let { start ->
            year(next)?.let { y -> LocalDate.of(y, start, 1).let { return Span(2, it, it.plusMonths(3)) } }
            val pick = (today.year - 2..today.year).map { LocalDate.of(it, start, 1) }.last { it <= today }
            return Span(1, pick, pick.plusMonths(3))
        }
        return null
    }

    private fun monthSpan(length: Int, year: Int, month: Int) =
        LocalDate.of(year, month, 1).let { Span(length, it, it.plusMonths(1)) }
}
