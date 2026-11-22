package dev.hkgill.gillspeak

/**
 * Streaming conversion of decoded PCM16 at any rate and channel count to the speech models' 16 kHz mono. Channels
 * are averaged, then a windowed-sinc low-pass resamples, so content above 8 kHz in a 44.1 or 48 kHz recording
 * doesn't fold back into the speech band. Call [finish] after the last [feed] for the final few milliseconds.
 */
internal class Resampler(inRate: Int, private val channels: Int, outRate: Int = Recorder.RATE, zeros: Int = 16) {
    init {
        require(inRate > 0 && channels > 0 && outRate > 0 && zeros > 0)
    }

    private val ratio = inRate.toDouble() / outRate
    private val passthrough = inRate == outRate
    private val cutoff = 0.92 * minOf(1.0, 1 / ratio) // of the input's Nyquist: a little under the output's
    private val half = Math.ceil(zeros / minOf(1.0, 1 / ratio)).toInt() // filter half-width, in input samples
    private var buf = DoubleArray(4096)
    private var len = 0
    private var base = 0L // absolute input index of buf[0]
    private var next = 0.0 // absolute input position of the next output sample
    private val taps = 2 * half

    /** The filter at [PHASES] fractional positions: row p, tap k is h(p / PHASES + half - 1 - k). */
    private val table = FloatArray((PHASES + 1) * taps).also { t ->
        for (p in 0..PHASES) for (k in 0 until taps) {
            val d = p.toDouble() / PHASES + half - 1 - k
            val x = Math.PI * cutoff * d
            val sinc = if (kotlin.math.abs(x) < 1e-9) 1.0 else kotlin.math.sin(x) / x
            t[p * taps + k] = (sinc * (0.5 + 0.5 * kotlin.math.cos(Math.PI * d / half))).toFloat()
        }
    }

    /** Converts [count] interleaved samples of [input]; calls [out] with each run of 16 kHz mono samples. */
    fun feed(input: ShortArray, count: Int, out: (ShortArray, Int) -> Unit) {
        require(count in 0..input.size && count % channels == 0)
        val frames = count / channels
        if (passthrough) {
            val result = ShortArray(frames)
            for (f in 0 until frames) {
                var sum = 0
                for (c in 0 until channels) sum += input[f * channels + c]
                result[f] = clamp(sum.toDouble() / channels)
            }
            if (frames > 0) out(result, frames)
            return
        }
        if (len + frames > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + frames))
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += input[f * channels + c]
            buf[len++] = sum.toDouble() / channels
        }
        emit(final = false, out)
    }

    fun finish(out: (ShortArray, Int) -> Unit) {
        if (!passthrough) emit(final = true, out)
    }

    private fun emit(final: Boolean, out: (ShortArray, Int) -> Unit) {
        val end = base + len // one past the last input sample
        val result = ShortArray(((end - next) / ratio).toInt().coerceAtLeast(0) + 2)
        var n = 0
        while (if (final) next < end else next + half + 1 < end) {
            var centre = Math.floor(next).toLong()
            var phase = Math.round((next - centre) * PHASES).toInt()
            if (phase == PHASES) { centre++; phase = 0 }
            val row = phase * taps
            var acc = 0.0
            var weight = 0.0
            // Taps outside the recording are left out and the rest renormalised, so the edges keep their level.
            val first = centre - half + 1
            for (k in (maxOf(base, first) - first).toInt()..(minOf(end - 1, centre + half) - first).toInt()) {
                val h = table[row + k]
                acc += buf[(first + k - base).toInt()] * h
                weight += h
            }
            result[n++] = clamp(if (weight > 1e-9) acc / weight else 0.0)
            next += ratio
        }
        if (n > 0) out(result, n)
        // Drop input no later output can reach.
        val keepFrom = (Math.floor(next).toLong() - half).coerceIn(base, end)
        val drop = (keepFrom - base).toInt()
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, len - drop)
            len -= drop
            base = keepFrom
        }
    }

    private fun clamp(v: Double) = Math.round(v).coerceIn(-32768L, 32767L).toInt().toShort()

    private companion object {
        const val PHASES = 256
    }
}

/**
 * Cuts a long 16 kHz mono recording into pieces of at most [maxSec] seconds for the speech models, which take one
 * dictation at a time. Each cut goes at the quietest moment in the last [searchSec] seconds before the limit, so it
 * rarely splits a word. [emit] gets each piece with its start, in samples from the beginning.
 */
internal class Segmenter(
    maxSec: Int = 30,
    searchSec: Int = 10,
    windowMs: Int = 200,
    private val rate: Int = Recorder.RATE,
    private val emit: (startSample: Long, pcm: ShortArray) -> Unit,
) {
    private val max = maxSec * rate
    private val search = searchSec * rate
    private val window = windowMs * rate / 1000
    private val buffer = ShortArray(max)
    private var filled = 0
    private var start = 0L

    init {
        require(searchSec in 1..maxSec && window in 1..search)
    }

    fun add(samples: ShortArray, count: Int) {
        var i = 0
        while (i < count) {
            val n = minOf(count - i, max - filled)
            System.arraycopy(samples, i, buffer, filled, n)
            filled += n
            i += n
            if (filled == max) cut()
        }
    }

    /** Emits whatever is left. */
    fun finish() {
        if (filled > 0) emitFirst(filled)
    }

    private fun cut() {
        // The quietest window (by energy) in the search span; the cut goes at its middle.
        var best = max
        var bestEnergy = Long.MAX_VALUE
        var from = max - search
        val step = maxOf(1, window / 2)
        while (from + window <= max) {
            var energy = 0L
            for (k in from until from + window) energy += buffer[k].toLong() * buffer[k]
            if (energy < bestEnergy) {
                bestEnergy = energy
                best = from + window / 2
            }
            from += step
        }
        emitFirst(best)
    }

    private fun emitFirst(n: Int) {
        emit(start, buffer.copyOf(n))
        System.arraycopy(buffer, n, buffer, 0, filled - n)
        filled -= n
        start += n
    }
}

/** A transcribed piece of a recording, [startMs] from its beginning. */
internal data class TranscriptPiece(val startMs: Long, val text: String)

internal object TranscriptText {
    /** "0:00", "4:05", "1:02:09". */
    fun stamp(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** The text to copy or share: one paragraph per piece, each led by where it starts. */
    fun format(pieces: List<TranscriptPiece>): String =
        pieces.filter { it.text.isNotBlank() }.joinToString("\n\n") { "[${stamp(it.startMs)}] ${it.text.trim()}" }

    /** Whether a piece is too quiet to be worth transcribing (silence returns nothing and costs a model run). */
    fun silent(pcm: ShortArray, threshold: Int = 300): Boolean = pcm.none { kotlin.math.abs(it.toInt()) >= threshold }
}
