package dev.hkgill.murmur

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * On-device speech recognition: NVIDIA Parakeet TDT 0.6B v3 (int8) through sherpa-onnx, the same model and
 * settings as the desktop app. Nothing leaves the phone. English plus 24 other European languages.
 *
 * The model (about 640 MB) is downloaded once from the sherpa-onnx project and every file is checked against
 * the SHA256 the desktop app pins. The loaded recognizer takes about 1 GB of memory, so it's released after a
 * few idle minutes and reloaded on the next dictation.
 */
object LocalAsr {
    private data class ModelFile(val name: String, val size: Long, val sha256: String)

    private const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"
    private val FILES = listOf(
        ModelFile("encoder.int8.onnx", 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
        ModelFile("decoder.int8.onnx", 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
        ModelFile("joiner.int8.onnx", 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
        ModelFile("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
    )
    val TOTAL_BYTES = FILES.sumOf { it.size }
    private const val IDLE_RELEASE_MS = 5 * 60_000L

    sealed interface Status {
        data object Missing : Status
        data class Downloading(val done: Long, val total: Long) : Status
        data class Failed(val reason: String) : Status
        data object Ready : Status
    }

    @Volatile var status: Status = Status.Missing
        private set

    private var recognizer: OfflineRecognizer? = null
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val release = Runnable { synchronized(lock) { recognizer?.release(); recognizer = null } }

    private fun dir(context: Context) = File(context.filesDir, "models/parakeet-tdt-0.6b-v3-int8")

    /** Ready when every file is present at its expected size (hashes are checked when downloading). */
    fun refresh(context: Context): Status {
        if (status is Status.Downloading) return status
        val d = dir(context)
        status = if (FILES.all { File(d, it.name).length() == it.size }) Status.Ready else Status.Missing
        return status
    }

    fun isReady(context: Context) = refresh(context) == Status.Ready

    /** Downloads the missing files on a background thread; [onProgress] is called on that thread. */
    fun download(context: Context, onProgress: (Status) -> Unit) {
        if (status is Status.Downloading) return
        val d = dir(context).apply { mkdirs() }
        status = Status.Downloading(0, TOTAL_BYTES)
        Thread({
            try {
                var done = 0L
                for (f in FILES) {
                    val target = File(d, f.name)
                    if (target.length() == f.size) {
                        done += f.size
                        continue
                    }
                    val part = File(d, f.name + ".part").apply { delete() }
                    val conn = URL("$BASE/${f.name}").openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    val md = MessageDigest.getInstance("SHA-256")
                    conn.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            var lastReport = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                md.update(buf, 0, n)
                                done += n
                                if (done - lastReport > 2_000_000) {
                                    if (done / 50_000_000 != lastReport / 50_000_000) Log.i(Dictation.TAG, "model download: ${done / 1_000_000} MB")
                                    lastReport = done
                                    status = Status.Downloading(done, TOTAL_BYTES)
                                    onProgress(status)
                                }
                            }
                        }
                    }
                    val sha = md.digest().joinToString("") { "%02x".format(it) }
                    if (sha != f.sha256) {
                        part.delete()
                        error("${f.name} failed its checksum")
                    }
                    part.renameTo(target)
                }
                status = Status.Ready
                Log.i(Dictation.TAG, "model download complete and verified")
            } catch (e: Exception) {
                Log.e(Dictation.TAG, "model download failed", e)
                status = Status.Failed(e.message ?: e.javaClass.simpleName)
            }
            onProgress(status)
        }, "model-download").start()
    }

    fun delete(context: Context) {
        synchronized(lock) { recognizer?.release(); recognizer = null }
        dir(context).deleteRecursively()
        status = Status.Missing
    }

    /** Loads the model ahead of the first dictation (a few seconds), on the caller's thread. */
    fun warm(context: Context) {
        if (isReady(context)) synchronized(lock) { load(context) }
    }

    /** Transcribes a WAV from [Recorder] (16 kHz mono PCM16). Blocking; call off the main thread. */
    fun transcribe(context: Context, wav: ByteArray): String {
        check(isReady(context)) { "the on-device model isn't downloaded" }
        val pcm = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(pcm.remaining()) { pcm.get(it) / 32768f }
        synchronized(lock) {
            main.removeCallbacks(release)
            val rec = load(context)
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, Recorder.RATE)
                rec.decode(stream)
                return rec.getResult(stream).text.trim()
            } finally {
                stream.release()
                main.postDelayed(release, IDLE_RELEASE_MS)
            }
        }
    }

    private fun load(context: Context): OfflineRecognizer {
        recognizer?.let { return it }
        val t0 = System.nanoTime()
        val d = dir(context)
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = Recorder.RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(d, "encoder.int8.onnx").path,
                    decoder = File(d, "decoder.int8.onnx").path,
                    joiner = File(d, "joiner.int8.onnx").path,
                ),
                tokens = File(d, "tokens.txt").path,
                numThreads = 4,
                modelType = "nemo_transducer",
            ),
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(null, config).also {
            recognizer = it
            Log.i(Dictation.TAG, "local model loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }
}
