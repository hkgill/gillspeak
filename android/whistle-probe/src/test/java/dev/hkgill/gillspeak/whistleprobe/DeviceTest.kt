package dev.hkgill.gillspeak.whistleprobe

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTest {
    @Test fun parsesCpuinfo() {
        val text = """
            processor	: 0
            Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
            CPU part	: 0xd05

            processor	: 1
            Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
            CPU part	: 0xd05
        """.trimIndent()
        val cpu = CpuInfo.parse(text)
        assertEquals(2, cpu.cores)
        assertEquals(listOf("Cortex-A55"), cpu.parts)
        assertEquals(
            listOf("NEON" to true, "FP16 maths" to true, "dot product" to true, "int8 matrix" to false, "SVE" to false),
            cpu.extensions(),
        )
    }

    @Test fun unknownPartsAndEmptyInput() {
        assertEquals(listOf("0xabc"), CpuInfo.parse("CPU part : 0xabc").parts)
        assertEquals(CpuInfo(0, emptyList(), emptySet()), CpuInfo.parse(""))
    }

    @Test fun realTimeFactor() {
        val t = Timing(audioSeconds = 5.0, loadMs = 120, transcribeMs = 2500)
        assertEquals(0.5, t.rtf, 1e-9)
        assertEquals("audio 5.0 s · load 120 ms · transcribe 2500 ms · RTF 0.50", t.summary())
        assertEquals(true, Timing(0.0, 0, 10).rtf.isNaN())
    }
}
