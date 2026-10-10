package dev.hkgill.gillspeak

import dev.hkgill.gillspeak.EndOfSpeech.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class EndOfSpeechTest {
    /** Feeds a reading every 50 ms from [levels] (each a level held for its duration in ms); returns the verdict and when. */
    private fun run(vararg levels: Pair<Float, Long>): Pair<Verdict, Long> {
        val e = EndOfSpeech(0)
        var t = 0L
        for ((level, ms) in levels) {
            val end = t + ms
            while (t < end) {
                t += 50
                val v = e.feed(level, t)
                if (v != Verdict.LISTENING) return v to t
            }
        }
        return Verdict.LISTENING to t
    }

    @Test fun quietRoomStopsAfterTheSpeech() {
        val (v, at) = run(0.01f to 1000, 0.7f to 2000, 0.01f to 5000)
        assertEquals(Verdict.FINISHED, v)
        assertEquals(3000 + 950L, at)
    }

    @Test fun noisyRoomStillStops() {
        // A TV at 0.15 the whole time: it used to keep the screen listening until the 30-second cap.
        val (v, at) = run(0.15f to 1000, 0.8f to 2000, 0.15f to 5000)
        assertEquals(Verdict.FINISHED, v)
        assertEquals(3000 + 950L, at)
    }

    @Test fun noiseAloneIsNothingSaid() {
        assertEquals(Verdict.NOTHING_SAID, run(0.15f to 10_000).first)
    }

    @Test fun speakingStraightAwayStillCounts() {
        // No quiet start to measure the room from: the floor is capped, so the speech is still heard.
        val (v, _) = run(0.7f to 2000, 0.02f to 2000)
        assertEquals(Verdict.FINISHED, v)
    }

    @Test fun shortPausesDontEndIt() {
        val (v, at) = run(0.01f to 500, 0.7f to 1000, 0.01f to 600, 0.7f to 1000, 0.01f to 3000)
        assertEquals(Verdict.FINISHED, v)
        assertEquals(3100 + 950L, at)
    }

    @Test fun softSpeechAfterALoudWordStillCounts() {
        // Regression (CodeRabbit): one 0.8 peak made 0.15 speech after it count as quiet, cutting the person off.
        val (v, at) = run(0.01f to 500, 0.8f to 200, 0.15f to 2000, 0.01f to 3000)
        assertEquals(Verdict.FINISHED, v)
        assertEquals(2700 + 950L, at)
    }

    @Test fun readingsBeforeTheMicStartsDontSetTheRoom() {
        // Regression (CodeRabbit): the recorder's placeholder 0 set the floor to 0, so a noisy room counted as speech.
        val e = EndOfSpeech(0)
        var t = 0L
        repeat(4) { t += 50; e.feed(0f, t, heard = false) }
        var v = Verdict.LISTENING
        while (v == Verdict.LISTENING) { t += 50; v = e.feed(0.15f, t) }
        assertEquals(Verdict.NOTHING_SAID, v)
    }

    @Test fun capsTheLength() {
        // Loud and changing the whole time (music): it gives up at the cap.
        val levels = Array(400) { (if (it % 2 == 0) 0.9f else 0.5f) to 100L }
        assertEquals(Verdict.TOO_LONG, run(*levels).first)
    }
}
