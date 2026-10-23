package dev.hkgill.gillspeak

internal data class SpeechModelFile(val name: String, val size: Long, val sha256: String)

/** Stable preference ids also name private model directories. */
enum class SpeechModel(val id: String, val title: String, val description: String) {
    PARAKEET("parakeet-tdt-0.6b-v3-int8", "Parakeet", "English and 24 other European languages"),
    WHISTLE("whistle", "Whistle", "Smaller download · English, German, French, Spanish, Italian, Dutch and Polish");

    internal val baseUrl: String get() = when (this) {
        PARAKEET -> "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"
        WHISTLE -> "https://huggingface.co/Cactus-Compute/whistle/resolve/741118687e1f008f06d1351a402a731274789790"
    }
    internal val files: List<SpeechModelFile> get() = when (this) {
        PARAKEET -> listOf(
            SpeechModelFile("encoder.int8.onnx", 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
            SpeechModelFile("decoder.int8.onnx", 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
            SpeechModelFile("joiner.int8.onnx", 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
            SpeechModelFile("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
        )
        WHISTLE -> listOf(SpeechModelFile("whistle.cact", 16_919_407, "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb"))
    }
    val totalBytes: Long get() = files.sumOf { it.size }

    companion object {
        /** Missing or unknown preferences must keep existing installs usable. */
        fun fromId(id: String?): SpeechModel = entries.firstOrNull { it.id == id } ?: PARAKEET
    }
}
