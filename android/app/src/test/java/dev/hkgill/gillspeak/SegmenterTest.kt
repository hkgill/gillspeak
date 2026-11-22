package dev.hkgill.gillspeak

import org.junit.Assert.*
import org.junit.Test

class SegmenterTest {
    private fun collect(input: ShortArray, inRate: Int, channels: Int, chunk: Int = 1000): ShortArray {
        val r = Resampler(inRate, channels)
        val out = mutableListOf<Short>()
        var i = 0
        while (i < input.size) {
            val n = minOf(chunk - chunk % channels, input.size - i)
            r.feed(input.copyOfRange(i, i + n), n) { s, k -> for (j in 0 until k) out += s[j] }
            i += n
        }
        r.finish { s, k -> for (j in 0 until k) out += s[j] }
        return out.toShortArray()
    }

    @Test fun sixteenKilohertzMonoPassesThrough() {
        val input = ShortArray(5000) { (it % 200 - 100).toShort() }
        assertArrayEquals(input, collect(input, 16_000, 1))
    }

    @Test fun stereoIsAveraged() {
        val input = shortArrayOf(1000, 3000, -2000, 0)
        assertArrayEquals(shortArrayOf(2000, -1000), collect(input, 16_000, 2))
    }

    @Test fun downsamplesToSixteenKilohertzKeepingLevel() {
        for (rate in listOf(48_000, 44_100, 32_000, 22_050)) {
            val out = collect(ShortArray(rate) { 1234 }, rate, 1, chunk = 777) // one second, odd read sizes
            assertTrue("$rate Hz gave ${out.size} samples", out.size in 15_999..16_001)
            assertTrue(out.all { it.toInt() in 1233..1235 })
        }
    }

    private fun tone(hz: Int, rate: Int, amplitude: Int = 10_000) =
        ShortArray(rate) { (amplitude * kotlin.math.sin(2 * Math.PI * hz * it / rate)).toInt().toShort() }

    private fun peakInMiddle(s: ShortArray) = s.drop(200).dropLast(200).maxOf { kotlin.math.abs(it.toInt()) }

    @Test fun downsamplingRemovesToneAboveTheNewNyquist() {
        // 12 kHz is inaudible to a 16 kHz model; unfiltered, it would fold down to 4 kHz.
        for (rate in listOf(48_000, 44_100)) assertTrue(peakInMiddle(collect(tone(12_000, rate), rate, 1)) < 150)
    }

    @Test fun downsamplingKeepsSpeechFrequencies() {
        for (hz in listOf(300, 1000, 3000, 6000)) {
            val peak = peakInMiddle(collect(tone(hz, 48_000), 48_000, 1))
            assertTrue("$hz Hz came out at $peak", peak in 9_300..10_300)
        }
    }

    @Test fun upsamplesEightKilohertzCallAudio() {
        val flat = collect(ShortArray(8000) { -700 }, 8_000, 1, chunk = 333)
        assertEquals(16_000, flat.size)
        assertTrue(flat.all { it.toInt() in -701..-699 })
        val peak = peakInMiddle(collect(tone(1000, 8_000), 8_000, 1))
        assertTrue("1 kHz came out at $peak", peak in 9_500..10_300)
    }

    @Test fun cutsAtTheQuietestMomentBeforeTheLimit() {
        val rate = Recorder.RATE
        val loud = { n: Int -> ShortArray(n) { if (it % 2 == 0) 8000 else -8000 } }
        // 25 s of speech, 1 s of silence, 20 s of speech: the first cut should land in the silence.
        val audio = loud(25 * rate) + ShortArray(rate) + loud(20 * rate)
        val pieces = mutableListOf<Pair<Long, Int>>()
        val seg = Segmenter(maxSec = 30, searchSec = 10) { start, pcm -> pieces += start to pcm.size }
        seg.add(audio, audio.size)
        seg.finish()
        assertEquals(2, pieces.size)
        val cut = pieces[0].second
        assertTrue("cut at ${cut.toDouble() / rate} s", cut in 25 * rate..26 * rate)
        assertEquals(cut.toLong(), pieces[1].first)
        assertEquals(audio.size, pieces.sumOf { it.second })
    }

    @Test fun neverEmitsAPieceLongerThanTheLimit() {
        val rate = Recorder.RATE
        val audio = ShortArray(95 * rate) { if (it % 2 == 0) 5000 else -5000 }
        val sizes = mutableListOf<Int>()
        val seg = Segmenter(maxSec = 30) { _, pcm -> sizes += pcm.size }
        var i = 0
        while (i < audio.size) { // as the decoder feeds it, in uneven runs
            val n = minOf(4093, audio.size - i)
            seg.add(audio.copyOfRange(i, i + n), n)
            i += n
        }
        seg.finish()
        assertTrue(sizes.all { it <= 30 * rate })
        assertEquals(audio.size, sizes.sum())
    }

    @Test fun formatsTimestampsAndSkipsEmptyPieces() {
        val text = TranscriptText.format(listOf(
            TranscriptPiece(0, "Hi, it's me."),
            TranscriptPiece(29_500, " "),
            TranscriptPiece(245_000, "See you then."),
            TranscriptPiece(3_729_000, "Bye."),
        ))
        assertEquals("[0:00] Hi, it's me.\n\n[4:05] See you then.\n\n[1:02:09] Bye.", text)
    }

    @Test fun silenceIsSkipped() {
        assertTrue(TranscriptText.silent(ShortArray(1000) { (it % 50 - 25).toShort() }))
        assertFalse(TranscriptText.silent(ShortArray(1000).also { it[500] = 4000 }))
    }
}
