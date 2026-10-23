package dev.hkgill.gillspeak.whistleprobe

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.hkgill.gillspeak.WhistleNative
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * One screen that answers "can this watch run Whistle?": the watch's ABIs and CPU extensions, then loading the model,
 * then timing a transcription. Every result also goes to `adb logcat -s whistle-probe`.
 */
class ProbeActivity : Activity() {
    private lateinit var scroll: ScrollView
    private lateinit var report: TextView
    private val work = Executors.newSingleThreadExecutor()
    private val prefs get() = getSharedPreferences("probe", MODE_PRIVATE)
    private val model get() = File(filesDir, MODEL_NAME)
    @Volatile private var verified = false // the model on disk passed its SHA256 check in this process

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val side = (resources.displayMetrics.widthPixels * 0.13f).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, (28 * dp).toInt(), side, (56 * dp).toInt())
        }
        report = TextView(this).apply { textSize = 13f; typeface = Typeface.MONOSPACE }
        column.addView(report)
        column.addView(button("Download model") { inBackground { download() } })
        column.addView(button("Benchmark, 5 s") { inBackground { benchmark() } })
        column.addView(button("Record 5 s, transcribe") { recordAndTranscribe() })
        scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)

        say(deviceReport())
        // The model loads in this process: an unsupported CPU instruction kills it before anything can be reported.
        if (prefs.getBoolean(LOADING, false)) {
            prefs.edit().putBoolean(LOADING, false).commit()
            say("⚠ Last time the app died while running Whistle. The engine probably uses CPU instructions this watch lacks.")
        }
        inBackground {
            say(when {
                modelOk() -> "Model: present and verified"
                model.exists() -> "Model: the copy on the watch failed its checksum; download it again"
                else -> "Model: not downloaded yet"
            })
        }
    }

    override fun onResume() {
        super.onResume()
        scroll.requestFocus() // the rotating bezel scrolls the focused view
    }

    override fun onDestroy() {
        work.shutdownNow()
        super.onDestroy()
    }

    private fun deviceReport(): String {
        val cpu = CpuInfo.parse(runCatching { File("/proc/cpuinfo").readText() }.getOrDefault(""))
        val memory = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return buildString {
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("CPU: ${cpu.cores} cores, ${cpu.parts.joinToString().ifEmpty { "?" }}")
            appendLine(cpu.extensions().joinToString { (name, has) -> "$name ${if (has) "yes" else "no"}" })
            append("RAM: ${memory.totalMem / MB} MB, ${memory.availMem / MB} MB free")
        }
    }

    private fun download() {
        if (modelOk()) return say("Model: already downloaded")
        model.delete() // missing, or failed its checksum (a copy pushed with adb may be anything)
        say("Downloading ${MODEL_SIZE / MB} MB…")
        val part = File(filesDir, "$MODEL_NAME.part")
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode != 200) throw IOException("the server answered ${conn.responseCode}")
            conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it, 1 shl 16) } }
            if (!matches(part)) throw IOException("the download failed its checksum")
            if (!part.renameTo(model)) throw IOException("can't save the model")
            verified = true
            say("Model: downloaded and verified")
        } finally {
            conn.disconnect()
            part.delete()
        }
    }

    /** Loads Whistle (timed, once per process) and transcribes [samples] (timed). */
    private fun transcribe(samples: FloatArray, label: String) {
        if (!modelOk()) return say("Download the model first (or the copy on the watch failed its checksum)")
        prefs.edit().putBoolean(LOADING, true).commit()
        val loadStart = SystemClock.elapsedRealtime()
        WhistleNative.load(model.path)
        val loadMs = SystemClock.elapsedRealtime() - loadStart
        val start = SystemClock.elapsedRealtime()
        val json = WhistleNative.transcribe(samples, ByteArray(0)).toString(Charsets.UTF_8)
        val transcribeMs = SystemClock.elapsedRealtime() - start
        prefs.edit().putBoolean(LOADING, false).commit()
        val text = runCatching { JSONObject(json).getString("text").trim() }.getOrDefault("?")
        val timing = Timing(samples.size / RATE.toDouble(), loadMs, transcribeMs)
        say("$label: ${timing.summary()}\nmemory ${Debug.getPss() / 1024} MB PSS\n“$text”")
    }

    /** Whether the model on disk is the pinned file: size, then SHA256 (once per process; it takes a moment). */
    private fun modelOk(): Boolean {
        if (!verified) verified = matches(model)
        return verified
    }

    private fun matches(file: File): Boolean {
        if (file.length() != MODEL_SIZE) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == MODEL_SHA256
    }

    /** Five seconds of quiet noise: times the engine without the microphone (the text is meaningless). */
    private fun benchmark() {
        val random = Random(1)
        transcribe(FloatArray(5 * RATE) { (random.nextFloat() - 0.5f) * 0.002f }, "Benchmark")
    }

    private fun recordAndTranscribe() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        say("Recording 5 s… speak now")
        inBackground { transcribe(record(5), "Recording") }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) recordAndTranscribe()
        else say("Microphone permission refused")
    }

    /** [seconds] of 16 kHz mono audio, kept in memory only. */
    @Suppress("MissingPermission") // checked by the caller
    private fun record(seconds: Int): FloatArray {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE * 2),
        )
        val pcm = ShortArray(seconds * RATE)
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IOException("the microphone isn't available")
            recorder.startRecording()
            var read = 0
            while (read < pcm.size) {
                val n = recorder.read(pcm, read, pcm.size - read)
                if (n <= 0) throw IOException("the microphone stopped ($n)")
                read += n
            }
        } finally {
            recorder.release()
        }
        return FloatArray(pcm.size) { pcm[it] / 32768f }
    }

    /** Runs [block] off the main thread and reports a failure instead of crashing. */
    private fun inBackground(block: () -> Unit) {
        work.execute {
            try {
                block()
            } catch (e: Throwable) { // UnsatisfiedLinkError included: the native library didn't load
                prefs.edit().putBoolean(LOADING, false).commit()
                say("Failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        runOnUiThread { if (!isDestroyed) report.append((if (report.text.isEmpty()) "" else "\n\n") + line) }
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = (10 * resources.displayMetrics.density).toInt()
        }
    }

    private companion object {
        const val TAG = "whistle-probe"
        const val LOADING = "loading"
        const val RATE = 16_000
        const val MB = 1024 * 1024
        // The same pinned file as the phone app's SpeechModel.WHISTLE.
        const val MODEL_NAME = "whistle.cact"
        const val MODEL_SIZE = 16_919_407L
        const val MODEL_SHA256 = "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb"
        const val MODEL_URL = "https://huggingface.co/Cactus-Compute/whistle/resolve/741118687e1f008f06d1351a402a731274789790/whistle.cact"
    }
}
