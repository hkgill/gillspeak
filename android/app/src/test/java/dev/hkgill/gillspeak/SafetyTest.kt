package dev.hkgill.gillspeak

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the independent review of the bubble and engines branch. */
class SafetyTest {
    @Test fun passwordFieldsAreDetected() {
        val text = InputType.TYPE_CLASS_TEXT
        assertTrue(isPasswordInput(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(isPasswordInput(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(isPasswordInput(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(isPasswordInput(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertTrue(isPasswordInput(text or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
    }

    @Test fun ordinaryFieldsAreNotPasswords() {
        assertFalse(isPasswordInput(InputType.TYPE_CLASS_TEXT))
        assertFalse(isPasswordInput(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(isPasswordInput(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(isPasswordInput(InputType.TYPE_CLASS_NUMBER))
        assertFalse(isPasswordInput(InputType.TYPE_CLASS_PHONE))
        assertFalse(isPasswordInput(InputType.TYPE_NULL))
    }

    @Test fun selfTestNamesCannotLeaveTheFilesDir() {
        assertTrue(isSafeTestFile("t.wav"))
        assertTrue(isSafeTestFile("clip_01-a.wav"))
        assertFalse(isSafeTestFile("../shared_prefs/gillspeak.xml"))
        assertFalse(isSafeTestFile("../t.wav"))
        assertFalse(isSafeTestFile("sub/t.wav"))
        assertFalse(isSafeTestFile("gillspeak.xml"))
        assertFalse(isSafeTestFile(".wav"))
        assertFalse(isSafeTestFile("/data/t.wav"))
    }

    private fun chat(content: String, finish: String) =
        """{"choices":[{"message":{"role":"assistant","content":${org.json.JSONObject.quote(content)}},"finish_reason":"$finish"}]}"""

    @Test fun groqCleanupMustFinishNormally() {
        assertEquals("Meet on Friday.", Groq.parseChat(chat("Meet on Friday.", "stop"), "meet on thursday sorry friday"))
        val e = assertThrows(GeminiError::class.java) { Groq.parseChat(chat("Meet on", "length"), "meet on friday at the office") }
        assertEquals("truncated", e.kind)
        assertThrows(GeminiError::class.java) { Groq.parseChat("""{"choices":[]}""", "x") }
    }

    @Test fun groqCleanupStripsEchoedWrappers() {
        assertEquals("Hello there.", Groq.parseChat(chat("<transcript>\"Hello there.\"</transcript>", "stop"), "hello there"))
    }
}
