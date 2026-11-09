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
            SpeechModelFile("encoder.int8.onnx", 652_282_294, "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479"),
            SpeechModelFile("decoder.int8.onnx", 11_845_274, "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5"),
            SpeechModelFile("joiner.int8.onnx", 6_355_277, "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98"),
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
