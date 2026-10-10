package dev.hkgill.gillspeak

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentTest {
    @Test fun dictations() {
        val r = Recent.parse("Oct 10 14:02:11  Local 812 ms (filler)\n  heard: sure i will send it um\n  typed: Sure, I will send it.")
        assertEquals(Recent("Oct 10 14:02:11", "Local", "sure i will send it um", "Sure, I will send it.", "", false), r)
        assertEquals("Groq", Recent.parse("Oct 9 09:00:00  Groq 640 ms (whisper 300 ms, ok)\n  heard: a\n  typed: A.").source)
    }

    @Test fun commands() {
        val r = Recent.parse("Oct 10 14:05:00  Command: open spotify\n  did: Open Spotify")
        assertEquals(Recent("Oct 10 14:05:00", Recent.SIDE_BUTTON, "open spotify", "Open Spotify", "", false), r)
    }

    @Test fun failuresAreNotes() {
        val r = Recent.parse("Oct 10 14:06:00  FAILED network: timeout")
        assertEquals(Recent("Oct 10 14:06:00", "", "", "", "FAILED network: timeout", true), r)
    }
}
