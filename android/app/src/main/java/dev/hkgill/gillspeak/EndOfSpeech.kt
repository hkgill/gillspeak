package dev.hkgill.gillspeak

/**
 * Has the person finished their side-button command? Fed [Recorder.level] (0..1, a chunk's peak) every 50 ms or so.
 *
 * A fixed "quiet" level never fires in a noisy room: a TV or a fan stays above it, so the screen listened until its
 * 30-second cap. So the room is measured first (the quietest of the first few readings, before most people start
 * talking), speech has to stand out from it, and once the person has spoken, quiet means well below how loud they were.
 */
class EndOfSpeech(private val start: Long) {
    enum class Verdict { LISTENING, FINISHED, NOTHING_SAID, TOO_LONG }

    var spoke = false
        private set
    private var readings = 0
    private var floor = 1f
    private var loudest = 0f
    private var lastLoud = start

    /** [heard]: the recorder has read audio. Before that the level is a placeholder 0, which mustn't set the floor. */
    fun feed(level: Float, now: Long, heard: Boolean = true): Verdict {
        if (heard && readings++ < FLOOR_READINGS) floor = minOf(floor, level)
        val room = minOf(floor, MAX_FLOOR) * ROOM_FACTOR // someone talking from the start mustn't count as the room
        // Relative to the loudest moment, but capped: one loud word mustn't make softer speech after it count as quiet.
        val relative = minOf(loudest * QUIET_FACTOR, MAX_RELATIVE)
        if (level > maxOf(SPEECH_LEVEL, room, relative)) {
            spoke = spoke || level > maxOf(SPEECH_LEVEL, room)
            if (spoke) lastLoud = now
        }
        if (spoke) loudest = maxOf(loudest, level)
        return when {
            spoke && now - lastLoud > SILENCE_MS -> Verdict.FINISHED
            !spoke && now - start > NOTHING_SAID_MS -> Verdict.NOTHING_SAID
            now - start > MAX_LISTEN_MS -> Verdict.TOO_LONG
            else -> Verdict.LISTENING
        }
    }

    companion object {
        const val SPEECH_LEVEL = 0.06f
        const val SILENCE_MS = 900L
        const val NOTHING_SAID_MS = 7000L
        const val MAX_LISTEN_MS = 30_000L
        private const val FLOOR_READINGS = 6 // about the first 300 ms
        private const val MAX_FLOOR = 0.2f
        private const val ROOM_FACTOR = 2.5f
        private const val QUIET_FACTOR = 0.3f // after speech, quiet is under 30% of the loudest reading...
        private const val MAX_RELATIVE = 0.12f // ...but never needs to be quieter than this
    }
}
