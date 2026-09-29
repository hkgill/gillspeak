package dev.hkgill.gillspeak

import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.hypot

/**
 * Drag-to-snooze: dropping the idle bubble on the target at the bottom of the screen hides it for a while. Night
 * snooze hides it every night between two times of day, e.g. 22:00 to 07:00. Times of day are minutes after
 * midnight. Plain arithmetic, kept apart from the service so it can be unit tested.
 */
object Snooze {
    /** The lengths offered in the app's settings. */
    val MINUTES = listOf(10, 30, 60, 240)
    const val DEFAULT_MINUTES = 10

    /** A stored length, or the default if it's missing or no longer one of the choices. */
    fun minutesOrDefault(stored: Int) = if (stored in MINUTES) stored else DEFAULT_MINUTES

    fun until(now: Long, minutes: Int) = now + minutes * 60_000L

    fun isActive(now: Long, until: Long) = now < until

    /** Is the dragged bubble's centre within [radius] of the target's centre? */
    fun over(bubbleX: Float, bubbleY: Float, targetX: Float, targetY: Float, radius: Float) =
        hypot(bubbleX - targetX, bubbleY - targetY) <= radius

    const val NIGHT_START = 22 * 60
    const val NIGHT_END = 7 * 60

    /** Is [time] inside the nightly window? It may cross midnight (22:00 to 07:00) or not (13:00 to 14:00). */
    fun inNight(time: LocalTime, start: Int, end: Int): Boolean {
        val m = time.hour * 60 + time.minute
        return when {
            start == end -> false
            start < end -> m in start until end
            else -> m >= start || m < end
        }
    }

    /** When the night holding [now] ends, or null if [now] isn't in one. */
    fun nightEnd(now: LocalDateTime, start: Int, end: Int): LocalDateTime? =
        if (inNight(now.toLocalTime(), start, end)) next(now, end) else null

    /** The next time the bubble's night snooze starts or ends, or null if the window is empty. */
    fun nextNightChange(now: LocalDateTime, start: Int, end: Int): LocalDateTime? =
        if (start == end) null else minOf(next(now, start), next(now, end))

    /** The first [minute] of the day strictly after [now]. */
    private fun next(now: LocalDateTime, minute: Int): LocalDateTime {
        val today = now.toLocalDate().atTime(minute / 60, minute % 60)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** 1320 → "22:00". The app shows times in the phone's own format; this is for tests and logs. */
    fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

    /** "10 min", "1 hour", "4 hours". */
    fun label(minutes: Int) = when {
        minutes % 60 != 0 -> "$minutes min"
        minutes == 60 -> "1 hour"
        else -> "${minutes / 60} hours"
    }
}
