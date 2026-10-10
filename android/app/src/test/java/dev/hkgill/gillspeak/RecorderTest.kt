package dev.hkgill.gillspeak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A microphone held by another app sends zeros; a real one, even in a quiet room, never does for long. */
class RecorderTest {
    @Test fun mostlyZerosMeansTheMicIsBusy() {
        assertTrue(Recorder.mostlyZero(chunks = 46, zeroChunks = 44)) // a click, then 2 s of zeros
        assertTrue(Recorder.mostlyZero(chunks = 10, zeroChunks = 8))
    }

    @Test fun realAudioIsNotBusy() {
        assertFalse(Recorder.mostlyZero(chunks = 46, zeroChunks = 0))
        assertFalse(Recorder.mostlyZero(chunks = 46, zeroChunks = 20)) // gaps between words
        assertFalse(Recorder.mostlyZero(chunks = 5, zeroChunks = 5)) // too short to judge
    }
}
