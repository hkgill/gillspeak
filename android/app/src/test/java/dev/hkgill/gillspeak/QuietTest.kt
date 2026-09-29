package dev.hkgill.gillspeak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietTest {
    @Test fun nearSilenceIsTooQuiet() {
        assertTrue(tooQuiet(1))
        assertTrue(tooQuiet(98)) // a real tap on the S25 with nothing said
        assertTrue(tooQuiet(QUIET_PEAK - 1))
    }

    @Test fun speechIsNot() {
        assertFalse(tooQuiet(QUIET_PEAK))
        assertFalse(tooQuiet(2_500))
        assertFalse(tooQuiet(32_767))
    }

    @Test fun zeroIsABlockedMicNotQuiet() {
        assertFalse(tooQuiet(0)) // handled separately, with its own message
    }
}
