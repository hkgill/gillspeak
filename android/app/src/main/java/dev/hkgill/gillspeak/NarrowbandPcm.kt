package dev.hkgill.gillspeak

/** Streaming mono PCM16 interpolation from legacy SCO's 8 kHz to ASR's 16 kHz, with no disk/network. */
internal class NarrowbandPcm {
    private var previous: Int? = null

    fun expand(input: ByteArray, size: Int, output: ByteArray): Int {
        require(size in 0..input.size && size % 2 == 0 && output.size >= size * 2)
        var out = 0
        for (i in 0 until size step 2) {
            val sample = (input[i].toInt() and 0xff) or (input[i + 1].toInt() shl 8)
            val midpoint = ((previous ?: sample) + sample) / 2
            output[out++] = midpoint.toByte()
            output[out++] = (midpoint shr 8).toByte()
            output[out++] = sample.toByte()
            output[out++] = (sample shr 8).toByte()
            previous = sample
        }
        return out
    }
}
