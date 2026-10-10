package dev.hkgill.gillspeak

import kotlin.math.roundToInt

/**
 * What a spoken command asks for. Parsing is plain rules on the transcript, on the phone: only commands on this
 * list ever run, never anything a model makes up.
 */
sealed interface Command {
    data class OpenApp(val name: String) : Command
    data class Timer(val seconds: Int) : Command
    /**
     * [hour] is 0..23. When no am or pm was said ([exact] false), [hour] is 1..12 as spoken and the alarm goes to
     * whichever of the two comes next: see [Commands.nextOccurrence].
     */
    data class Alarm(val hour: Int, val minute: Int, val exact: Boolean = true) : Command
    data class Torch(val on: Boolean) : Command
    data class Media(val action: MediaAction) : Command
    /** [target] is the recipient and the message together ("Sam I'm running late"); see [Commands.splitRecipient]. */
    data class Text(val target: String) : Command
    data class Call(val who: String) : Command
    data class Navigate(val place: String) : Command
    data class Search(val query: String) : Command
    /** A new calendar event. [title] may be empty; [day] and [time] are the words said ("tomorrow", "3 pm"), or null. */
    data class Event(val title: String, val day: String?, val time: String?) : Command
}

enum class MediaAction { PLAY, PAUSE, NEXT, PREVIOUS }

object Commands {
    private fun re(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val POLITE = re("""^(?:(?:hey|ok|okay)\s+gillspeak|please|can you|could you|would you|i want to|i'd like to)[\s,]+""")
    private val TORCH = re("""^(?:turn|switch|put)\s+(on|off)\s+(?:the\s+|my\s+)?(?:torch|flash\s?light)$|^(?:turn|switch|put)\s+(?:the\s+|my\s+)?(?:torch|flash\s?light)\s+(on|off)$|^(?:torch|flash\s?light)\s+(on|off)$""")
    private val PAUSE = re("""^(?:pause|stop)(?:\s+(?:the\s+)?(?:music|song|playback|podcast|audio))?$""")
    private val PLAY = re("""^(?:play|resume|continue)(?:\s+(?:the\s+)?(?:music|song|playback|podcast|audio))?$""")
    private val NEXT = re("""^(?:next|skip)(?:\s+(?:this\s+)?(?:song|track))?$|^(?:play\s+)?(?:the\s+)?next\s+(?:song|track)$""")
    private val PREVIOUS = re("""^(?:previous|last)(?:\s+(?:song|track))?$|^(?:play\s+)?(?:the\s+)?(?:previous|last)\s+(?:song|track)$|^go\s+back\s+a\s+(?:song|track)$""")
    private const val SET = """(?:(?:set(?:\s+up)?|start|make|create|add|put(?:\s+on)?|give\s+me)\s+)?"""
    private val TIMER_FOR = re("""^$SET(?:a\s+|an\s+|me\s+a\s+)?(?:new\s+)?timer\s+(?:for\s+|of\s+)?(.+)$""")
    private val TIMER_AFTER = re("""^$SET(?:a\s+|an\s+)?(.+?)\s+timer$""")
    private val ALARM = re("""^$SET(?:me\s+)?(?:an\s+|my\s+|the\s+|a\s+)?(?:new\s+)?alarm\s+(?:for|at|to)\s+(.+)$|^wake\s+me(?:\s+up)?\s+(?:at\s+)?(.+)$""")
    private val TEXT = re("""^(?:text|message|sms|send\s+(?:a\s+)?(?:text|message|sms)\s+to|send|tell)\s+(.+)$""")
    private val CALL = re("""^(?:call|phone|ring|dial)\s+(.+)$""")
    private val NAVIGATE = re("""^(?:navigate|directions|get\s+directions|take\s+me|drive|give\s+me\s+directions)\s+(?:to\s+)?(.+)$""")
    private val EVENT = re("""^(?:add|create|make|schedule|book|put\s+in|set\s+up|new)\s+(?:me\s+)?(?:an?\s+|new\s+)*(?:calendar\s+)?(event|meeting|appointment|booking)\b\s*(.*)$""")
    private val TO_CALENDAR = re("""^(?:add|put|schedule)\s+(.+?)\s+(?:to|in|on|into)\s+(?:my\s+|the\s+)?calendar\b\s*(.*)$""")
    private val SEARCH = re("""^(?:search(?:\s+the\s+web|\s+google)?(?:\s+for)?|google|look\s+up)\s+(.+)$""")
    private val OPEN = re("""^(?:open|launch|start|run|show|go\s+to)\s+(?:up\s+)?(?:the\s+|my\s+)?(.+?)(?:\s+app)?$""")

    /** The command in [spoken], or null when it isn't one of the commands gillspeak knows. */
    fun parse(spoken: String): Command? {
        var s = spoken.trim().trimEnd('.', '!', '?', ',', ';').replace(Regex("\\s+"), " ")
        while (true) s = POLITE.replace(s, "").takeIf { it != s } ?: break
        if (s.isEmpty()) return null
        val plain = s.replace(Regex("[,;!?]"), "").trim()

        TORCH.matchEntire(plain)?.let { m -> return Command.Torch(m.groupValues.drop(1).first { it.isNotEmpty() }.lowercase() == "on") }
        if (PAUSE.matches(plain)) return Command.Media(MediaAction.PAUSE)
        if (NEXT.matches(plain)) return Command.Media(MediaAction.NEXT)
        if (PREVIOUS.matches(plain)) return Command.Media(MediaAction.PREVIOUS)
        if (PLAY.matches(plain)) return Command.Media(MediaAction.PLAY)
        (TIMER_FOR.matchEntire(plain) ?: TIMER_AFTER.matchEntire(plain))?.let { m ->
            duration(m.groupValues[1])?.let { return Command.Timer(it) }
        }
        ALARM.matchEntire(plain)?.let { m ->
            clockTime(m.groupValues.drop(1).first { it.isNotEmpty() })?.let { (h, min, exact) -> return Command.Alarm(h, min, exact) }
        }
        EVENT.matchEntire(plain)?.let { m ->
            val noun = m.groupValues[1].lowercase()
            return event(m.groupValues[2], if (noun == "event") "" else noun.replaceFirstChar { it.uppercase() })
        }
        TO_CALENDAR.matchEntire(plain)?.let { m -> return event(m.groupValues[1] + " " + m.groupValues[2], "") }
        // Messages keep their own punctuation, so these match the text before commas were dropped.
        TEXT.matchEntire(s)?.let { m -> return Command.Text(m.groupValues[1].trim()) }
        CALL.matchEntire(plain)?.let { m -> return Command.Call(m.groupValues[1].trim()) }
        NAVIGATE.matchEntire(plain)?.let { m -> return Command.Navigate(m.groupValues[1].trim()) }
        SEARCH.matchEntire(plain)?.let { m -> return Command.Search(m.groupValues[1].trim()) }
        OPEN.matchEntire(plain)?.let { m -> return Command.OpenApp(m.groupValues[1].trim()) }
        return null
    }

    // ---- Calendar events ----

    private const val WEEKDAY = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
    private val EVENT_DAY = re("""\b(?:on\s+|this\s+|next\s+)?(today|tonight|tomorrow|the\s+day\s+after\s+tomorrow|$WEEKDAY)\b""")
    private val EVENT_TIME = re("""\b(?:at|from|for)\s+(.+?)(?=\s+(?:on|with|called|about|today|tonight|tomorrow|next|this)\b|$)""")
    private val EVENT_LEAD = re("""^(?:to\s+(?:my\s+|the\s+)?calendar\b|called|named|titled|about|for|:)\s*""")

    /** Splits what followed "add an event" into a title, a day and a time; [kind] ("Meeting") leads the title. */
    private fun event(rest: String, kind: String): Command.Event {
        var s = rest.trim().removeSuffix(".")
        var day: String? = null
        EVENT_DAY.find(s)?.let { m -> day = m.groupValues[1].lowercase(); s = s.removeRange(m.range) }
        var time: String? = null
        EVENT_TIME.findAll(s).firstOrNull { clockTime(it.groupValues[1]) != null }?.let { m ->
            time = m.groupValues[1].trim().replace(re("""^(?:(?:at|from|for)\s+)+"""), "") // "for at 9 am"
            s = s.removeRange(m.range)
        }
        s = s.replace(Regex("\\s+"), " ").trim()
        while (true) s = EVENT_LEAD.replace(s, "").trim().takeIf { it != s } ?: break
        s = s.replace(re("""\s+(?:to|in|on|into)\s+(?:my\s+|the\s+)?calendar$"""), "").trim()
        s = s.replace(re("""(?:\s+(?:for|on|at|from))+$"""), "").trim() // left over once the day or time was taken out
        val title = listOf(kind, s).filter { it.isNotEmpty() }.joinToString(" ").replaceFirstChar { it.uppercaseChar() }
        return Command.Event(title, day, time)
    }

    data class EventTime(val start: java.time.LocalDateTime, val allDay: Boolean)

    /**
     * When an event starts, from the [day] and [time] said, relative to [now]; null when neither was said. A time
     * without am or pm reads as a working day would: 8 to 11 is morning, 12 is noon, 1 to 7 is afternoon or evening.
     * A weekday is the next one after today. With no day, a time that has passed today means tomorrow.
     */
    fun eventStart(spokenDay: String?, time: String?, now: java.time.LocalDateTime): EventTime? {
        val day = spokenDay?.lowercase()?.trim()?.replace(Regex("\\s+"), " ")?.removePrefix("on ")?.removePrefix("next ")?.removePrefix("this ")
        val clock = time?.let(::clockTime)
        if (day == null && clock == null) return null
        val today = now.toLocalDate()
        val date = when (day) {
            null, "today", "tonight" -> today
            "tomorrow" -> today.plusDays(1)
            "the day after tomorrow" -> today.plusDays(2)
            else -> {
                val wanted = java.time.DayOfWeek.valueOf(day.uppercase())
                generateSequence(today.plusDays(1)) { it.plusDays(1) }.first { it.dayOfWeek == wanted }
            }
        }
        if (clock == null) return EventTime(date.atStartOfDay(), allDay = true)
        var hour = clock.hour
        // An unspecified hour reads as working hours: "at 3" is 15:00, and "quarter to 1" (hour 0 here) is 12:45.
        if (!clock.exact) hour = if (day == "tonight" || hour in 1..7) hour % 12 + 12 else if (hour == 0) 12 else hour
        var start = date.atTime(hour, clock.minute)
        if (day == null && start.isBefore(now)) start = start.plusDays(1)
        return EventTime(start, allDay = false)
    }

    // ---- Recipients ----

    private val SAYING = re("""^(.+?)\s*(?::|,|\s+saying|\s+that|\s+to\s+say)\s+(.+)$""")

    /**
     * Splits "Sam I'm running late" into the recipient and the message. An explicit "saying", "that" or colon
     * decides it; otherwise the longest run of up to three leading words that [isContact] knows is the recipient.
     */
    fun splitRecipient(target: String, isContact: (String) -> Boolean): Pair<String, String>? {
        val t = target.trim().removePrefix("to ").trim()
        SAYING.matchEntire(t)?.let { m ->
            val who = m.groupValues[1].trim()
            if (isContact(who)) return who to sentence(m.groupValues[2])
        }
        val words = t.split(' ')
        for (n in minOf(3, words.size - 1) downTo 1) {
            val who = words.take(n).joinToString(" ")
            if (isContact(who)) return who to sentence(words.drop(n).joinToString(" "))
        }
        return null
    }

    /** Capitalised, ending in punctuation: what the message would look like typed. */
    fun sentence(s: String): String {
        val t = s.trim().removePrefix("saying ").trim()
        if (t.isEmpty()) return t
        val first = t.replaceFirstChar { it.uppercaseChar() }
        return if (first.last() in ".!?") first else "$first."
    }

    // ---- Matching names ----

    fun normalise(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** The index of the label that best matches [query] (an app name as heard), or null if none is close. */
    fun bestMatch(query: String, labels: List<String>): Int? {
        val q = normalise(query)
        if (q.isEmpty()) return null
        val qs = q.replace(" ", "")
        var best: Int? = null
        var bestScore = Int.MAX_VALUE
        labels.forEachIndexed { i, label ->
            val l = normalise(label)
            if (l.isEmpty()) return@forEachIndexed
            val ls = l.replace(" ", "")
            val score = when {
                l == q -> 0
                ls == qs -> 1 // "you tube" for YouTube
                l.split(' ').contains(q) || ls.startsWith(qs) -> 2 // "maps" for Google Maps, "spot" for Spotify
                qs.length >= 4 && ls.contains(qs) -> 3
                qs.length >= 4 && editDistance(ls, qs) <= qs.length / 4 -> 4 // a mishearing: "spottify"
                else -> return@forEachIndexed
            } * 1000 + ls.length // shorter labels win ties: "Camera" before "Camera Assistant"
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    // ---- Numbers, durations and times ----

    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90)

    /** Reads a number at [i]: digits ("10", "1.5"), words ("twenty five") or "a"/"an". Returns it and the next index. */
    private fun number(tokens: List<String>, i: Int): Pair<Double, Int>? {
        val w = tokens.getOrNull(i) ?: return null
        w.toDoubleOrNull()?.let { return it to i + 1 }
        if (w == "a" || w == "an") return 1.0 to i + 1
        UNITS[w]?.let { return it.toDouble() to i + 1 }
        TENS[w]?.let { tens ->
            UNITS[tokens.getOrNull(i + 1)]?.takeIf { it in 1..9 }?.let { return (tens + it).toDouble() to i + 2 }
            return tens.toDouble() to i + 1
        }
        return null
    }

    private fun tokens(s: String) = s.lowercase()
        .replace(Regex("(\\d)-(?=[a-z])"), "$1 ") // "10-minute"
        .replace(Regex("([a-z])-([a-z])"), "$1 $2") // "twenty-five"
        .replace(Regex("(\\d)([a-z])"), "$1 $2") // "10min", "7pm"
        .split(Regex("[\\s,]+")).filter { it.isNotEmpty() }

    private fun unitSeconds(w: String?): Int? = when (w) {
        "h", "hr", "hrs", "hour", "hours" -> 3600
        "m", "min", "mins", "minute", "minutes" -> 60
        "s", "sec", "secs", "second", "seconds" -> 1
        else -> null
    }

    /** "10 minutes", "an hour and a half", "half an hour", "1 hour 20 minutes", "90 seconds": seconds, or null. */
    fun duration(spoken: String): Int? {
        val t = tokens(spoken)
        var i = 0
        var total = 0.0
        var any = false
        while (i < t.size) {
            when {
                t[i] == "and" || t[i] == "for" -> i++
                t[i] == "half" && (t.getOrNull(i + 1) == "an" || t.getOrNull(i + 1) == "a") && unitSeconds(t.getOrNull(i + 2)) != null -> {
                    total += unitSeconds(t[i + 2])!! / 2.0
                    any = true
                    i += 3
                }
                else -> {
                    val (n, next) = number(t, i) ?: return null
                    var j = next
                    // "one and a half hours"
                    var amount = n
                    if (t.getOrNull(j) == "and" && t.getOrNull(j + 1) == "a" && t.getOrNull(j + 2) == "half") {
                        amount += 0.5
                        j += 3
                    }
                    val unit = unitSeconds(t.getOrNull(j)) ?: return null
                    total += amount * unit
                    any = true
                    j++
                    // "an hour and a half"
                    if (t.getOrNull(j) == "and" && t.getOrNull(j + 1) == "a" && t.getOrNull(j + 2) == "half") {
                        total += unit / 2.0
                        j += 3
                    }
                    i = j
                }
            }
        }
        val seconds = total.roundToInt()
        return seconds.takeIf { any && it in 1..MAX_TIMER_S }
    }

    /** "7", "7am", "6:30 pm", "seven thirty", "half past 6", "quarter to 8 in the morning": hour (0..23) and minute. */
    data class ClockTime(val hour: Int, val minute: Int, val exact: Boolean)

    /** Hour 0..23 when am/pm (or "tonight", "in the morning", a 24-hour hour) settles it; otherwise 1..12 and not [ClockTime.exact]. */
    fun clockTime(spoken: String): ClockTime? {
        val norm = spoken.lowercase().replace('\u2019', '\'')
            .replace(Regex("\\b([ap])\\.\\s?m\\.?"), "$1m")
            .replace(Regex("(\\d{1,2})[:.](\\d{2})"), "$1 $2")
            .replace("o'clock", "").replace("oclock", "")
            .replace(Regex("\\bnoon\\b"), "12 pm").replace(Regex("\\bmidnight\\b"), "12 am")
        var t = tokens(norm).toMutableList()
        var pm: Boolean? = null
        fun drop(vararg phrase: String, isPm: Boolean) {
            val i = t.windowed(phrase.size).indexOf(phrase.toList())
            if (i >= 0) {
                repeat(phrase.size) { t.removeAt(i) }
                pm = isPm
            }
        }
        drop("am", isPm = false); drop("pm", isPm = true)
        drop("in", "the", "morning", isPm = false); drop("in", "the", "afternoon", isPm = true)
        drop("in", "the", "evening", isPm = true); drop("at", "night", isPm = true); drop("tonight", isPm = true)
        t = t.filter { it != "at" && it != "for" }.toMutableList()

        var hour: Int
        var minute = 0
        var hourBefore = false // "quarter to 12": the hour named is the one after; am/pm belongs to that hour
        val rel = t.indexOf("past").takeIf { it > 0 } ?: t.indexOf("to").takeIf { it > 0 }
        if (rel != null) { // "half past 6", "quarter to 8", "20 past 7"
            val before = t.subList(0, rel).filter { it != "a" }
            val mins = when (before.joinToString(" ")) {
                "half" -> 30
                "quarter" -> 15
                else -> number(before, 0)?.takeIf { it.second == before.size || before.getOrNull(it.second)?.startsWith("min") == true }?.first?.toInt()
            } ?: return null
            val (h, end) = number(t, rel + 1) ?: return null
            if (end != t.size) return null
            hour = h.toInt()
            if (t[rel] == "past") minute = mins else { minute = 60 - mins; hourBefore = true }
        } else {
            val (h, next) = number(t, 0) ?: return null
            hour = h.toInt()
            if (next < t.size) {
                if (t.getOrNull(next) == "oh") { // "seven oh five"
                    val (m, end) = number(t, next + 1) ?: return null
                    if (end != t.size) return null
                    minute = m.toInt()
                } else {
                    val (m, end) = number(t, next) ?: return null
                    if (end != t.size) return null
                    minute = m.toInt()
                }
            }
            if (h != h.toInt().toDouble()) return null
        }
        if (minute !in 0..59) return null
        when (pm) {
            true -> { if (hour !in 1..12) return null; if (hour != 12) hour += 12 }
            false -> { if (hour !in 1..12) return null; if (hour == 12) hour = 0 }
            null -> if (hour !in 0..23) return null
        }
        val exact = pm != null || hour == 0 || hour > 12
        if (hourBefore) hour = (hour + 23) % 24 // "quarter to 12 pm" is 11:45, "quarter to 1 am" is 00:45
        return ClockTime(hour, minute, exact)
    }

    /** "Three o'clock" at 10:08 am means 3 pm: the next time the clock shows [hour]:[minute], on a 12-hour face. */
    fun nextOccurrence(hour: Int, minute: Int, nowMinutes: Int): Pair<Int, Int> {
        val am = hour % 12
        val candidates = listOf(am, am + 12).map { it * 60 + minute }
        val next = candidates.minBy { (it - nowMinutes + 24 * 60 - 1) % (24 * 60) } // strictly after now
        return next / 60 to next % 60
    }

    const val MAX_TIMER_S = 24 * 3600
}
