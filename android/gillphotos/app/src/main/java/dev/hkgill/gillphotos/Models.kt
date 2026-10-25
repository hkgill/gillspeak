package dev.hkgill.gillphotos

import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import dev.hkgill.gillphotos.core.FaceModels
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

const val TAG = "gillphotos"

private val background = Executors.newSingleThreadScheduledExecutor()

private fun hex(md: MessageDigest) = md.digest().joinToString("") { "%02x".format(it) }

/**
 * EmbeddingGemma 2 through LiteRT-LM: photos and searches become vectors in one space, so "dog on a beach" lands
 * near pictures of one. Google's files on Hugging Face are gated (sign in, accept the terms), so the app can't fetch
 * one itself: the user downloads the text + vision (440M) or full (740M) `.litertlm` on the phone and imports it.
 * Loads on the GPU, falls back to the CPU, and is released after five idle minutes.
 */
object Embedder {
    private const val FILE = "embeddinggemma-2.litertlm"
    private const val IDLE_RELEASE_MS = 5 * 60_000L
    /** EmbeddingGemma's prompt for search queries. Whether it's the best one for finding photos is still to measure. */
    private const val QUERY = "task: search result | query: "

    private val lock = Any()
    private var engine: EmbeddingEngine? = null
    private var releaseTask: ScheduledFuture<*>? = null
    var backend = ""
        private set

    fun file(context: Context) = File(context.filesDir, "models/$FILE")
    fun isReady(context: Context) = file(context).length() > 0
    private fun prefs(context: Context) = context.getSharedPreferences("embedder", Context.MODE_PRIVATE)

    /** What was imported: the picked file's name and its SHA256, so a good file can be pinned later. */
    fun describe(context: Context): String? = prefs(context).getString("name", null)?.let { "$it · sha256 ${prefs(context).getString("sha", "")?.take(12)}…" }

    /** Image tokens per photo (0: the model's own default). Fewer is faster; more sees smaller detail. */
    fun tokens(context: Context) = prefs(context).getInt("tokens", 0)
    fun setTokens(context: Context, tokens: Int) = prefs(context).edit().putInt("tokens", tokens).apply()

    /**
     * Copies a picked model into private storage, checksumming on the way, then checks it can read both text and
     * pictures. Worker thread only. Throws with a reason the user can act on.
     */
    fun import(context: Context, uri: Uri, name: String, progress: (Long) -> Unit) {
        release()
        val part = File(file(context).path + ".part").also { it.parentFile?.mkdirs() }
        val md = MessageDigest.getInstance("SHA-256")
        try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                        done += n
                        progress(done)
                    }
                }
            }
            check(part.renameTo(file(context))) { "couldn't move the model into place" }
            text(context, "a test")
            val probe = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
            runCatching { image(context, Library.jpeg(probe)) }.onFailure {
                throw IllegalStateException("this model can't read pictures: pick the text + vision (440M) or full (740M) EmbeddingGemma 2 file", it)
            }
            prefs(context).edit().putString("name", name).putString("sha", hex(md)).apply()
            Log.i(TAG, "embedder imported: $name on $backend")
        } catch (e: Exception) {
            release()
            part.delete()
            file(context).delete()
            throw e
        }
    }

    fun delete(context: Context) {
        release()
        file(context).delete()
        prefs(context).edit().remove("name").remove("sha").apply()
    }

    /** A photo (JPEG or PNG bytes) as a unit-length vector. Worker thread only. */
    fun image(context: Context, picture: ByteArray): FloatArray {
        val tokens = tokens(context).takeIf { it > 0 }
        return try {
            embed(context, InputData.Image(picture), tokens)
        } catch (e: Exception) {
            if (tokens == null) throw e
            // This model file may not have that many image tokens: go back to its default.
            Log.w(TAG, "embedder: $tokens image tokens failed, using the model's default", e)
            setTokens(context, 0)
            embed(context, InputData.Image(picture), null)
        }
    }

    /** A search as a unit-length vector. Worker thread only. */
    fun text(context: Context, query: String): FloatArray = embed(context, InputData.Text(QUERY + query), null)

    private fun embed(context: Context, input: InputData, tokens: Int?): FloatArray {
        try {
            // Under the lock, so the idle release can't close the engine mid-call.
            return synchronized(lock) {
                load(context).computeEmbedding(listOf(input), EmbeddingOptions(normalize = true, visionTokensPerImage = tokens)).embedding
            }
        } finally {
            scheduleRelease()
        }
    }

    private fun load(context: Context): EmbeddingEngine {
        engine?.let { return it }
        val t0 = SystemClock.uptimeMillis()
        val path = file(context).path
        fun start(b: () -> Backend) = EmbeddingEngine(
            EmbeddingEngineConfig(modelPath = path, backend = b(), visionBackend = b(), cacheDir = context.cacheDir.path),
        ).apply { initialize() }
        val e = runCatching { start { Backend.GPU() }.also { backend = "GPU" } }.getOrElse {
            Log.w(TAG, "embedder: GPU unavailable, using the CPU", it)
            start { Backend.CPU() }.also { backend = "CPU" }
        }
        Log.i(TAG, "embedder loaded on $backend in ${SystemClock.uptimeMillis() - t0} ms")
        engine = e
        return e
    }

    fun release() = synchronized(lock) {
        releaseTask?.cancel(false)
        engine?.close()
        engine = null
    }

    private fun scheduleRelease() = synchronized(lock) {
        releaseTask?.cancel(false)
        releaseTask = background.schedule(Runnable { release(); Log.i(TAG, "embedder released") }, IDLE_RELEASE_MS, TimeUnit.MILLISECONDS)
    }
}

/** YuNet and SFace from the APK's assets, loaded once (10 MB). */
object FaceKit {
    @Volatile private var models: FaceModels? = null

    fun get(context: Context): FaceModels = models ?: synchronized(this) {
        models ?: FaceModels(
            context.assets.open("face_detection_yunet_2023mar.onnx").use { it.readBytes() },
            context.assets.open("face_recognition_sface_2021dec_int8.onnx").use { it.readBytes() },
        ).also { models = it }
    }
}

/**
 * Gemma 4 E2B (optional, 2.6 GB) on the GPU: asked about one photo at a time ("what's the total on this receipt?").
 * The same pinned, SHA256-checked file as gillspeak's LocalLlm; Android keeps each app's files to itself, so
 * gillphotos downloads its own copy. Released after ten idle minutes.
 */
object Gemma {
    private const val FILE = "gemma-4-E2B-it.litertlm"
    private const val SIZE = 2_588_147_712L
    private const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    private const val URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
        "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/$FILE"
    private const val IDLE_RELEASE_MS = 10 * 60_000L

    sealed class Status {
        object Missing : Status()
        class Downloading(val done: Long, val total: Long, val waitingForWifi: Boolean) : Status()
        object Installing : Status()
        object Ready : Status()
        class Failed(val reason: String) : Status()
    }

    class Reply(val text: String, val ms: Long, val loadMs: Long)

    private val lock = Any()
    private var engine: Engine? = null
    private var releaseTask: ScheduledFuture<*>? = null
    @Volatile private var installing = false
    @Volatile private var failure: String? = null

    fun file(context: Context) = File(context.filesDir, "models/$FILE")
    fun isReady(context: Context) = file(context).length() == SIZE
    private fun dm(context: Context) = context.getSystemService(DownloadManager::class.java)
    private fun prefs(context: Context) = context.getSharedPreferences("gemma_download", Context.MODE_PRIVATE)
    private fun pendingId(context: Context) = prefs(context).getLong("id", -1).takeIf { it >= 0 }
    private fun setPending(context: Context, id: Long?) =
        prefs(context).edit().apply { if (id == null) remove("id") else putLong("id", id) }.apply()
    private fun downloaded(context: Context) = File(context.getExternalFilesDir("gemma-download"), FILE)

    /** Where the model stands; a finished download starts installing. */
    fun refresh(context: Context): Status {
        if (installing) return Status.Installing
        if (isReady(context)) return Status.Ready
        failure?.let { return Status.Failed(it) }
        val id = pendingId(context) ?: return Status.Missing
        dm(context).query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return fail(context, "the download was cancelled")
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> { install(context); Status.Installing }
                DownloadManager.STATUS_FAILED -> fail(context, "the download failed (error $reason)")
                else -> Status.Downloading(
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0), SIZE,
                    reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI,
                )
            }
        }
    }

    private fun fail(context: Context, reason: String): Status {
        cancel(context)
        failure = reason
        return Status.Failed(reason)
    }

    /** Queues the 2.6 GB download: Wi-Fi only, resumable, with a progress notification. */
    fun download(context: Context) {
        failure = null
        if (isReady(context) || pendingId(context) != null) return
        downloaded(context).delete()
        val request = DownloadManager.Request(Uri.parse(URL))
            .setTitle("gillphotos Gemma model")
            .setDescription(FILE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, "gemma-download", FILE)
        setPending(context, dm(context).enqueue(request))
    }

    fun cancel(context: Context) {
        pendingId(context)?.let { dm(context).remove(it) }
        setPending(context, null)
    }

    private fun install(context: Context) {
        if (installing) return
        installing = true
        background.execute {
            val part = File(file(context).path + ".part").also { it.parentFile?.mkdirs() }
            try {
                val md = MessageDigest.getInstance("SHA-256")
                downloaded(context).inputStream().use { input ->
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
                if (hex(md) != SHA256) {
                    part.delete()
                    fail(context, "the model failed its checksum")
                } else {
                    part.renameTo(file(context))
                    cancel(context) // also deletes the downloaded copy
                }
            } catch (e: Exception) {
                Log.e(TAG, "gemma install failed", e)
                part.delete() // a full disk can stop the copy part way: don't leave gigabytes behind
                fail(context, e.message ?: e.javaClass.simpleName)
            } finally {
                installing = false
            }
        }
    }

    fun delete(context: Context) {
        synchronized(lock) {
            releaseTask?.cancel(false)
            engine?.close()
            engine = null
        }
        cancel(context)
        file(context).delete()
        failure = null
    }

    /** One photo and one question, in a fresh conversation, greedy so the same question gets the same answer. */
    fun ask(context: Context, picture: ByteArray, question: String): Reply {
        val t0 = SystemClock.uptimeMillis()
        var loadMs = 0L
        val text = try {
            synchronized(lock) {
                val tl = SystemClock.uptimeMillis()
                val e = load(context)
                loadMs = SystemClock.uptimeMillis() - tl
                val config = ConversationConfig(
                    samplerConfig = SamplerConfig(1, 1.0, 0.0, 0),
                    maxOutputToken = 512,
                    thinkingConfig = ThinkingConfig(false),
                )
                e.createConversation(config).use { c ->
                    c.sendMessage(Contents.of(Content.ImageBytes(picture), Content.Text(question)))
                        .contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
                }
            }
        } finally {
            scheduleRelease()
        }
        return Reply(text, SystemClock.uptimeMillis() - t0, loadMs)
    }

    private fun load(context: Context): Engine {
        engine?.let { return it }
        val path = file(context).path
        fun start(b: () -> Backend) = Engine(
            EngineConfig(modelPath = path, backend = b(), visionBackend = b(), maxNumTokens = 4096, cacheDir = context.cacheDir.path),
        ).apply { initialize() }
        return runCatching { start { Backend.GPU() } }.getOrElse {
            Log.w(TAG, "gemma: GPU unavailable, using the CPU", it)
            start { Backend.CPU() }
        }.also { engine = it }
    }

    private fun scheduleRelease() = synchronized(lock) {
        releaseTask?.cancel(false)
        releaseTask = background.schedule(Runnable {
            synchronized(lock) { engine?.close(); engine = null }
        }, IDLE_RELEASE_MS, TimeUnit.MILLISECONDS)
    }
}
