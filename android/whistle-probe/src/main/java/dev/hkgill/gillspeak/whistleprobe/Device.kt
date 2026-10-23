package dev.hkgill.gillspeak.whistleprobe

import java.util.Locale

/** What /proc/cpuinfo says about the CPU, for the report. Pure, so it's tested on the JVM. */
data class CpuInfo(val cores: Int, val parts: List<String>, val features: Set<String>) {
    /** The instruction-set extensions that matter for a quantised speech model, and whether this CPU has them. */
    fun extensions(): List<Pair<String, Boolean>> = EXTENSIONS.map { (flag, name) -> name to (flag in features) }

    companion object {
        private val EXTENSIONS = listOf(
            "asimd" to "NEON",
            "asimdhp" to "FP16 maths",
            "asimddp" to "dot product",
            "i8mm" to "int8 matrix",
            "sve" to "SVE",
        )

        // ARM's CPU part numbers for the cores in watch chips (and a few phone ones, to make reports readable).
        private val PARTS = mapOf(
            "0xd03" to "Cortex-A53", "0xd04" to "Cortex-A35", "0xd05" to "Cortex-A55", "0xd46" to "Cortex-A510",
            "0xd80" to "Cortex-A520", "0xd0b" to "Cortex-A76", "0xd41" to "Cortex-A78", "0xd47" to "Cortex-A710",
        )

        fun parse(text: String): CpuInfo {
            val lines = text.lines().map { it.substringBefore(':').trim().lowercase() to it.substringAfter(':', "").trim() }
            val features = lines.filter { it.first == "features" }.flatMap { it.second.split(' ') }.filter { it.isNotEmpty() }.toSet()
            val parts = lines.filter { it.first == "cpu part" }.map { PARTS[it.second.lowercase()] ?: it.second }.distinct()
            val cores = lines.count { it.first == "processor" }
            return CpuInfo(cores, parts, features)
        }
    }
}

/** The numbers one run produced: how long loading and transcribing took against the audio's length. */
data class Timing(val audioSeconds: Double, val loadMs: Long, val transcribeMs: Long) {
    /** Real-time factor: below 1 means faster than the audio played; 0.5 is twice as fast. */
    val rtf get() = if (audioSeconds > 0) transcribeMs / 1000.0 / audioSeconds else Double.NaN

    fun summary() = String.format(Locale.ROOT, "audio %.1f s · load %d ms · transcribe %d ms · RTF %.2f", audioSeconds, loadMs, transcribeMs, rtf)
}
