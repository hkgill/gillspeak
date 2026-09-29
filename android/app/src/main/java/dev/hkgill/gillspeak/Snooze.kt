package dev.hkgill.gillspeak

import kotlin.math.hypot

/**
 * Drag-to-snooze: dropping the idle bubble on the target at the bottom of the screen hides it for a while.
 * Plain arithmetic, kept apart from the service so it can be unit tested.
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

    /** "10 min", "1 hour", "4 hours". */
    fun label(minutes: Int) = when {
        minutes % 60 != 0 -> "$minutes min"
        minutes == 60 -> "1 hour"
        else -> "${minutes / 60} hours"
    }
}
