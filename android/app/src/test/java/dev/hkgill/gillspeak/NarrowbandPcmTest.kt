package dev.hkgill.gillspeak

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NarrowbandPcmTest {
    private fun pcm(vararg values: Int) = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach { putShort(it.toShort()) } }.array()

    @Test fun interpolatesSignedSamplesAndPreservesEndpoints() {
        val output = ByteArray(12)
        assertEquals(12, NarrowbandPcm().expand(pcm(-32768, 0, 32767), 6, output))
        assertArrayEquals(pcm(-32768, -32768, -16384, 0, 16383, 32767), output)
    }

    @Test fun interpolationIsContinuousAcrossReadBoundaries() {
        val converter = NarrowbandPcm()
        val output = ByteArray(4)
        converter.expand(pcm(1000), 2, output)
        converter.expand(pcm(-1000), 2, output)
        assertArrayEquals(pcm(0, -1000), output)
    }

    @Test fun upsampledAudioKeepsDurationInTheExistingWavFormat() {
        val output = ByteArray(1600)
        val bytes = NarrowbandPcm().expand(ByteArray(800), 800, output)
        assertEquals(1600, bytes)
        assertEquals(50L, Recorder.durationMs(Recorder.wav(output)))
    }
}
