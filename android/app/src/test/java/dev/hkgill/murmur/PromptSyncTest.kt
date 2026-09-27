package dev.hkgill.murmur

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The app ships a copy of the desktop prompt; fail if the two drift apart. Tests run from android/app. */
class PromptSyncTest {
    @Test fun cleanPromptMatchesDesktop() {
        assertEquals(
            "Re-copy murmur/prompts/clean_v2.txt into android/app/src/main/assets/",
            File("../../murmur/prompts/clean_v2.txt").readText(),
            File("src/main/assets/clean_v2.txt").readText(),
        )
    }
}
