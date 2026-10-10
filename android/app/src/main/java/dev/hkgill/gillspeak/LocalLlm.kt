package dev.hkgill.gillspeak

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * On-device language model: Google's Gemma 4 E2B through LiteRT-LM, on the GPU. Nothing leaves the phone.
 * Parakeet ([LocalAsr]) hears; this understands: commands the rules miss, and polishing dictation.
 *
 * The model (2.6 GB, from Google's litert-community on Hugging Face, pinned to one revision) is fetched by Android's
 * DownloadManager over Wi-Fi and checked against its SHA256 before it's used. It holds 2-3 GB of memory while
 * loaded, so it loads on first use and is released after ten idle minutes. Calls are serialised: one
 * conversation at a time.
 */
object LocalLlm {
    const val FILE = "gemma-4-E2B-it.litertlm"
    const val SIZE = 2_588_147_712L
    const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    // Loading takes 4-7 s of GPU work, so a loaded model is kept for the next command a while; holding memory costs
    // next to no battery.
    private const val IDLE_RELEASE_MS = 10 * 60_000L
    private const val URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
        "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/$FILE"
    private const val DOWNLOAD_DIR = "gemma-download"

    data class Reply(val text: String, val ms: Long, val loadMs: Long)

    private var engine: Engine? = null
    private var backend = "" // what the loaded engine runs on, for the log
    private val lock = Any()
    private val background = Executors.newSingleThreadScheduledExecutor()
    private var releaseTask: ScheduledFuture<*>? = null

    fun file(context: Context) = File(context.filesDir, "models/$FILE")

    fun isReady(context: Context) = file(context).length() == SIZE

    // ---- Download: one file, like LocalAsr's ----

    @Volatile private var installing = false
    @Volatile private var failure: String? = null

    private fun dm(context: Context) = context.getSystemService(DownloadManager::class.java)
    private fun prefs(context: Context) = context.getSharedPreferences("gemma_download", Context.MODE_PRIVATE)
    private fun pendingId(context: Context) = prefs(context).getLong("id", -1).takeIf { it >= 0 }
    private fun setPending(context: Context, id: Long?) =
        prefs(context).edit().apply { if (id == null) remove("id") else putLong("id", id) }.apply()
    private fun downloaded(context: Context) = File(context.getExternalFilesDir(DOWNLOAD_DIR), FILE)

    /** Whether a DownloadManager id is ours (for the completion broadcast). */
    fun owns(context: Context, id: Long) = id == pendingId(context)

    /** Where the model stands; a finished download starts installing. Same states as Parakeet's. */
    fun refresh(context: Context): LocalAsr.Status {
        if (installing) return LocalAsr.Status.Installing
        if (isReady(context)) return LocalAsr.Status.Ready
        failure?.let { return LocalAsr.Status.Failed(it) }
        val id = pendingId(context) ?: return LocalAsr.Status.Missing
        dm(context).query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return fail(context, "the download was cancelled")
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> { install(context); LocalAsr.Status.Installing }
                DownloadManager.STATUS_FAILED -> fail(context, "the download failed (error $reason)")
                else -> LocalAsr.Status.Downloading(
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0), SIZE,
                    reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI,
                )
            }
        }
    }

    private fun fail(context: Context, reason: String): LocalAsr.Status {
        cancelDownload(context)
        failure = reason
        return LocalAsr.Status.Failed(reason)
    }

    /** Queues the 2.6 GB download: Wi-Fi only, resumable, with a progress notification. */
    fun download(context: Context) {
        failure = null
        if (isReady(context) || pendingId(context) != null) return
        downloaded(context).delete() // DownloadManager won't overwrite a leftover
        val request = DownloadManager.Request(Uri.parse(URL))
            .setTitle("${Settings.APP_NAME} Gemma model")
            .setDescription(FILE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, DOWNLOAD_DIR, FILE)
        setPending(context, dm(context).enqueue(request))
        Log.i(Dictation.TAG, "gemma download queued")
    }

    fun cancelDownload(context: Context) {
        pendingId(context)?.let { dm(context).remove(it) }
        setPending(context, null)
    }

    /** Copies the finished download into private storage, checking its SHA256 on the way. Calls [onDone] when over. */
    fun install(context: Context, onDone: () -> Unit = {}) {
        if (installing) return onDone()
        installing = true
        background.execute {
            try {
                val src = downloaded(context)
                val part = partFile(context).also { it.parentFile?.mkdirs() }
                val md = java.security.MessageDigest.getInstance("SHA-256")
                src.inputStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
                if (md.digest().joinToString("") { "%02x".format(it) } != SHA256) {
                    part.delete()
                    fail(context, "the model failed its checksum")
                    Log.e(Dictation.TAG, "gemma install: checksum mismatch")
                } else {
                    part.renameTo(file(context))
                    pendingId(context)?.let { dm(context).remove(it) } // also deletes the downloaded copy
                    setPending(context, null)
                    Log.i(Dictation.TAG, "gemma download complete and verified")
                }
            } catch (e: Exception) {
                Log.e(Dictation.TAG, "gemma install failed", e)
                partFile(context).delete() // a full disk can stop the copy part way: don't leave gigabytes behind
                fail(context, e.message ?: e.javaClass.simpleName)
            } finally {
                installing = false
                onDone()
            }
        }
    }

    /** Unloads and deletes the model in the background, then calls [onDone]. */
    fun delete(context: Context, onDone: () -> Unit) {
        background.execute {
            synchronized(lock) {
                releaseTask?.cancel(false)
                engine?.close()
                engine = null
                cancelDownload(context)
                file(context).delete()
                partFile(context).delete()
                failure = null
            }
            onDone()
        }
    }

    private fun partFile(context: Context) = File(file(context).path + ".part")

    /** Loads the model ahead of time (a few seconds), so the first command doesn't wait for it. Worker thread only. */
    fun warm(context: Context) {
        if (!isReady(context)) return
        synchronized(lock) { load(context) }
        scheduleRelease()
    }

    /**
     * One question, one answer, in a fresh conversation so nothing carries over between commands. Starting a
     * conversation is nearly free (under 10 ms); answering a command takes about 0.4 s on a Galaxy S25's GPU.
     * Worker thread only.
     */
    fun ask(context: Context, system: String, user: String, maxTokens: Int = 256): Reply {
        val t0 = SystemClock.uptimeMillis()
        val text: String
        val loadMs: Long
        try {
            synchronized(lock) {
                val tl = SystemClock.uptimeMillis()
                val e = load(context)
                loadMs = SystemClock.uptimeMillis() - tl
                val config = ConversationConfig(
                    systemInstruction = Contents.of(system),
                    samplerConfig = SamplerConfig(1, 1.0, 0.0, 0), // greedy: the same words always get the same answer
                    maxOutputToken = maxTokens,
                    thinkingConfig = ThinkingConfig(false),
                )
                text = e.createConversation(config).use { c ->
                    c.sendMessage(user).contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
                }
            }
        } finally {
            scheduleRelease() // a failed answer must not keep 2.6 GB loaded for good
        }
        val ms = SystemClock.uptimeMillis() - t0
        Log.i(Dictation.TAG, "gemma ($backend) ${ms - loadMs} ms${if (loadMs > 50) " + load $loadMs ms" else ""}")
        return Reply(text, ms, loadMs)
    }

    private fun load(context: Context): Engine {
        engine?.let { return it }
        val t0 = SystemClock.uptimeMillis()
        val path = file(context).path
        // The GPU is several times faster; fall back to the CPU on phones whose GPU driver LiteRT-LM can't use.
        val e = runCatching { start(path, Backend.GPU(), context).also { backend = "GPU" } }
            .getOrElse {
                Log.w(Dictation.TAG, "gemma: GPU unavailable, using the CPU", it)
                start(path, Backend.CPU(), context).also { backend = "CPU" }
            }
        Log.i(Dictation.TAG, "gemma loaded on $backend in ${SystemClock.uptimeMillis() - t0} ms")
        engine = e
        return e
    }

    private fun start(path: String, backend: Backend, context: Context): Engine =
        Engine(EngineConfig(modelPath = path, backend = backend, maxNumTokens = 2048, cacheDir = context.cacheDir.path))
            .apply { initialize() }

    /** Frees the model after [IDLE_RELEASE_MS] without use (restarts the countdown). */
    private fun scheduleRelease() {
        releaseTask?.cancel(false)
        releaseTask = background.schedule({
            synchronized(lock) {
                engine?.close()
                engine = null
                Log.i(Dictation.TAG, "gemma released")
            }
        }, IDLE_RELEASE_MS, TimeUnit.MILLISECONDS)
    }
}
