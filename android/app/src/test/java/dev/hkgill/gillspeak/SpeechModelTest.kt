package dev.hkgill.gillspeak

import org.junit.Assert.*
import org.junit.Test

class SpeechModelTest {
    @Test fun existingOrUnknownPreferencesKeepParakeet() {
        assertEquals(SpeechModel.PARAKEET, SpeechModel.fromId(null))
        assertEquals(SpeechModel.PARAKEET, SpeechModel.fromId("removed-engine"))
        assertEquals(SpeechModel.WHISTLE, SpeechModel.fromId("whistle"))
    }

    @Test fun parakeetDownloadAndDirectoryStayCompatible() {
        assertEquals("parakeet-tdt-0.6b-v3-int8", SpeechModel.PARAKEET.id)
        assertEquals(670_478_772L, SpeechModel.PARAKEET.totalBytes)
        assertEquals(listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
            SpeechModel.PARAKEET.files.map { it.name })
    }

    @Test fun whistleIsAnIndependentPinnedDownload() {
        assertEquals(16_919_407L, SpeechModel.WHISTLE.totalBytes)
        assertEquals("whistle.cact", SpeechModel.WHISTLE.files.single().name)
        assertFalse(SpeechModel.WHISTLE.baseUrl.contains("/main"))
        SpeechModel.entries.forEach { model ->
            model.files.forEach { assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}"))) }
        }
    }
}
