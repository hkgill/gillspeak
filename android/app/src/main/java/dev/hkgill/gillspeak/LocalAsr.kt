package dev.hkgill.gillspeak

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import dev.hkgill.gillspeak.LocalAsr.Status
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The selected speech engine, shared by every recording entry point. */
object LocalAsr {
    sealed interface Status {
        data object Missing : Status
        data class Downloading(val done: Long, val total: Long, val waitingForWifi: Boolean) : Status
        data object Installing : Status
        data class Failed(val reason: String) : Status
        data object Ready : Status
    }

    private val engines = SpeechModel.entries.associateWith { AsrEngine(it) }
    private fun engine(model: SpeechModel) = engines.getValue(model)
    fun selected(context: Context) = Settings(context).speechModel
    fun isReady(context: Context, model: SpeechModel = selected(context)) = engine(model).isReady(context)
    fun refresh(context: Context, model: SpeechModel = selected(context)) = engine(model).refresh(context)
    fun download(context: Context, model: SpeechModel = selected(context)) = engine(model).download(context)
    fun cancelDownloads(context: Context, model: SpeechModel = selected(context)) = engine(model).cancelDownloads(context)
    fun delete(context: Context, model: SpeechModel = selected(context), onDone: () -> Unit) = engine(model).delete(context, onDone)
    fun owns(context: Context, id: Long) = engines.values.any { it.owns(context, id) }
    fun installDownload(context: Context, id: Long, onDone: () -> Unit) {
        val owner = engines.values.firstOrNull { it.owns(context, id) }
        if (owner == null) onDone() else owner.install(context, onDone)
    }
    fun warm(context: Context) = engine(selected(context)).warm(context)
    fun transcribe(context: Context, wav: ByteArray, model: SpeechModel = selected(context)): String =
        engine(model).transcribe(context, wav, Settings(context).dictionary().bias)
}

/** A model's independent downloads, installation state and recognizer. */
private class AsrEngine(private val model: SpeechModel) {
    private val files get() = model.files
    private val totalBytes get() = model.totalBytes
    private val baseUrl get() = model.baseUrl
    private val downloadDirectory get() = if (model == SpeechModel.PARAKEET) "model-download" else "model-download/${model.id}"
    private val downloadPrefs get() = if (model == SpeechModel.PARAKEET) "model_download" else "model_download_${model.id}"
    private val idleReleaseMs = 5 * 60_000L

    private var recognizer: OfflineRecognizer? = null
    private val lock = Any()
    // Releasing, deleting and installing wait on file or native work, so they happen here, never on the main thread.
    private val background = Executors.newSingleThreadScheduledExecutor()
    private var releaseTask: ScheduledFuture<*>? = null
    @Volatile private var installing = false
    private val installWaiters = mutableListOf<() -> Unit>()
    @Volatile private var failure: String? = null

    /** Frees the ~1 GB recognizer after [idleReleaseMs] without use (restarts the countdown). */
    private fun scheduleRelease() {
        releaseTask?.cancel(false)
        releaseTask = background.schedule({
            synchronized(lock) {
                recognizer?.let {
                    it.release()
                    Log.i(Dictation.TAG, "local model released")
                }
                recognizer = null
            }
        }, idleReleaseMs, TimeUnit.MILLISECONDS)
    }

    private fun dir(context: Context) = File(context.filesDir, "models/${model.id}")
    private fun downloads(context: Context) = context.getExternalFilesDir(downloadDirectory)
    private fun dm(context: Context) = context.getSystemService(DownloadManager::class.java)

    /** DownloadManager ids of files still being fetched, by file name. Kept in preferences: downloads outlive us. */
    private fun pending(context: Context): MutableMap<String, Long> {
        val prefs = context.getSharedPreferences(downloadPrefs, Context.MODE_PRIVATE)
        return files.mapNotNull { f -> prefs.getLong(f.name, -1).takeIf { it >= 0 }?.let { f.name to it } }.toMap().toMutableMap()
    }

    private fun savePending(context: Context, ids: Map<String, Long>) {
        context.getSharedPreferences(downloadPrefs, Context.MODE_PRIVATE).edit().clear().apply {
            ids.forEach { (name, id) -> putLong(name, id) }
        }.apply()
    }

    private fun installed(context: Context, f: SpeechModelFile) = File(dir(context), f.name).length() == f.size

    fun isReady(context: Context) = files.all { installed(context, it) }

    /** Where the model stands. When every file has finished downloading, this starts installing them. */
    fun refresh(context: Context): Status {
        if (installing) return Status.Installing
        if (isReady(context)) return Status.Ready
        failure?.let { return Status.Failed(it) }
        val ids = pending(context)
        if (ids.isEmpty()) return Status.Missing
        var done = files.filter { installed(context, it) }.sumOf { it.size }
        var waitingForWifi = false
        var finished = 0
        dm(context).query(DownloadManager.Query().setFilterById(*ids.values.toLongArray())).use { c ->
            while (c.moveToNext()) {
                done += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> finished++
                    DownloadManager.STATUS_FAILED -> return fail(context, "the download failed (error $reason)")
                    DownloadManager.STATUS_PAUSED -> if (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) waitingForWifi = true
                }
            }
            // Cancelled from the notification: DownloadManager forgets the download entirely.
            if (c.count < ids.size) return fail(context, "the download was cancelled")
        }
        // Normally the completion broadcast installs finished files; this catches any it missed.
        if (finished > 0) install(context)
        return if (finished == ids.size) Status.Installing else Status.Downloading(done, totalBytes, waitingForWifi)
    }

    private fun fail(context: Context, reason: String): Status {
        cancelDownloads(context)
        failure = reason
        return Status.Failed(reason)
    }

    /**
     * Hands the missing files to Android's DownloadManager: it keeps going when the app is closed, shows progress
     * in a notification, resumes after network drops, and waits for Wi-Fi.
     */
    fun download(context: Context) {
        failure = null
        check(dir(context).isDirectory || dir(context).mkdirs()) { "Cannot create model directory" }
        val ids = pending(context)
        for (f in files) {
            if (installed(context, f) || f.name in ids) continue
            File(downloads(context), f.name).delete() // DownloadManager won't overwrite a leftover
            val request = DownloadManager.Request(Uri.parse("$baseUrl/${f.name}"))
                .setTitle("${Settings.APP_NAME} ${model.title}")
                .setDescription(f.name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(false)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(context, downloadDirectory, f.name)
            ids[f.name] = dm(context).enqueue(request)
        }
        savePending(context, ids)
        Log.i(Dictation.TAG, "model download queued: ${ids.keys}")
    }

    fun cancelDownloads(context: Context) {
        val ids = pending(context)
        if (ids.isNotEmpty()) dm(context).remove(*ids.values.toLongArray())
        savePending(context, emptyMap())
    }

    /** Whether a DownloadManager id is one of ours (for the completion broadcast). */
    fun owns(context: Context, id: Long) = id in pending(context).values

    private fun succeeded(context: Context, ids: Collection<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val ok = mutableSetOf<Long>()
        dm(context).query(DownloadManager.Query().setFilterById(*ids.toLongArray()).setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL)).use { c ->
            while (c.moveToNext()) ok += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
        }
        return ok
    }

    /**
     * Moves the files DownloadManager has finished into private storage, checking each one's SHA256 on the way;
     * a mismatch cancels everything. Runs in the background. [onDone] is called when this run (or the one already
     * in progress) is over, whatever the outcome.
     */
    fun install(context: Context, onDone: () -> Unit = {}) {
        synchronized(installWaiters) {
            installWaiters += onDone
            if (installing) return
            installing = true
        }
        background.execute {
            try {
                val ids = pending(context)
                val done = succeeded(context, ids.values)
                for (f in files) {
                    val id = ids[f.name]?.takeIf { it in done } ?: continue
                    val src = File(downloads(context), f.name)
                    val part = File(dir(context), f.name + ".part")
                    val md = MessageDigest.getInstance("SHA-256")
                    src.inputStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                md.update(buf, 0, n)
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    if (md.digest().joinToString("") { "%02x".format(it) } != f.sha256) {
                        part.delete()
                        fail(context, "${f.name} failed its checksum")
                        Log.e(Dictation.TAG, "model install: ${f.name} failed its checksum")
                        return@execute
                    }
                    check(part.length() == f.size && part.renameTo(File(dir(context), f.name))) { "Cannot install ${f.name}" }
                    dm(context).remove(id) // also deletes the downloaded copy
                    ids.remove(f.name)
                    savePending(context, ids)
                }
                if (isReady(context)) Log.i(Dictation.TAG, "model download complete and verified")
            } catch (e: Exception) {
                Log.e(Dictation.TAG, "model install failed", e)
                files.forEach { File(dir(context), it.name + ".part").delete() } // don't leave a part-copied file behind
                fail(context, e.message ?: e.javaClass.simpleName)
            } finally {
                val waiters = synchronized(installWaiters) {
                    installing = false
                    installWaiters.toList().also { installWaiters.clear() }
                }
                waiters.forEach { it() }
            }
        }
    }

    /** Deletes the model in the background (it may have to wait for a dictation to finish), then calls [onDone]. */
    fun delete(context: Context, onDone: () -> Unit) {
        background.execute {
            synchronized(lock) {
                releaseTask?.cancel(false)
                recognizer?.release()
                recognizer = null
                cancelDownloads(context)
                dir(context).deleteRecursively()
                failure = null
            }
            onDone()
        }
    }

    /** Loads the model ahead of the first dictation (a few seconds), on the caller's thread. */
    fun warm(context: Context) {
        if (!isReady(context)) return
        synchronized(lock) {
            if (model == SpeechModel.WHISTLE) WhistleNative.load(File(dir(context), "whistle.cact").path) else load(context)
            if (model == SpeechModel.PARAKEET) scheduleRelease() // release Parakeet after idle
        }
    }

    /** Transcribes a WAV from [Recorder] (16 kHz mono PCM16). Blocking; call off the main thread. */
    fun transcribe(context: Context, wav: ByteArray, keywords: List<String>): String {
        check(isReady(context)) { "the on-device model isn't downloaded" }
        require(wav.size >= 44 && (wav.size - 44) % 2 == 0) { "Invalid recording" }
        val pcm = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(pcm.remaining()) { pcm.get(it) / 32768f }
        synchronized(lock) {
            releaseTask?.cancel(false)
            check(isReady(context)) { "the on-device model isn't downloaded" }
            if (model == SpeechModel.WHISTLE) {
                WhistleNative.load(File(dir(context), "whistle.cact").path)
                val bias = keywords.joinToString("\n").toByteArray(Charsets.UTF_8)
                val json = WhistleNative.transcribe(samples, bias).toString(Charsets.UTF_8)
                return JSONObject(json).getString("text").trim()
            }
            val rec = load(context)
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, Recorder.RATE)
                rec.decode(stream)
                return rec.getResult(stream).text.trim()
            } finally {
                stream.release()
                scheduleRelease()
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
