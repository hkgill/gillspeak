package dev.hkgill.gillphotos

import android.app.DownloadManager
import android.content.Context
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
 * One model file from Hugging Face, pinned to a revision and checked against its SHA256: downloaded by Android's
 * DownloadManager (Wi-Fi only, resumable, with a progress notification), then copied into private storage with the
 * checksum checked on the way, as gillspeak does for Gemma.
 */
class PinnedModel(
    private val name: String,
    private val file: String,
    val size: Long,
    private val sha256: String,
    private val url: String,
) {
    sealed class Status {
        object Missing : Status()
        class Downloading(val done: Long, val total: Long, val waitingForWifi: Boolean) : Status()
        object Installing : Status()
        object Ready : Status()
        class Failed(val reason: String) : Status()
    }

    @Volatile private var installing = false
    @Volatile private var failure: String? = null
    private val dir = "download-" + file.substringBefore('.')

    fun file(context: Context) = File(context.filesDir, "models/$file")
    fun isReady(context: Context) = file(context).length() == size
    private fun dm(context: Context) = context.getSystemService(DownloadManager::class.java)
    private fun prefs(context: Context) = context.getSharedPreferences("download_$file", Context.MODE_PRIVATE)
    private fun pendingId(context: Context) = prefs(context).getLong("id", -1).takeIf { it >= 0 }
    private fun setPending(context: Context, id: Long?) =
        prefs(context).edit().apply { if (id == null) remove("id") else putLong("id", id) }.apply()
    private fun downloaded(context: Context) = File(context.getExternalFilesDir(dir), file)

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
                    c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0), size,
                    reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI,
                )
            }
        }
    }

    /** Short text for a status line: "downloading, 40%", "ready". */
    fun describe(context: Context): String = when (val s = refresh(context)) {
        Status.Missing -> "not downloaded"
        is Status.Downloading -> "downloading, ${s.done * 100 / s.total}%" + if (s.waitingForWifi) ", waiting for Wi-Fi" else ""
        Status.Installing -> "checking the download"
        Status.Ready -> "ready"
        is Status.Failed -> "failed: ${s.reason}"
    }

    private fun fail(context: Context, reason: String): Status {
        cancel(context)
        failure = reason
        return Status.Failed(reason)
    }

    fun download(context: Context) {
        failure = null
        if (isReady(context) || pendingId(context) != null) return
        downloaded(context).delete() // DownloadManager won't overwrite a leftover
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("gillphotos: $name")
            .setDescription(file)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, dir, file)
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
                if (hex(md) != sha256) {
                    part.delete()
                    fail(context, "the model failed its checksum")
                } else {
                    part.renameTo(file(context))
                    cancel(context) // also deletes the downloaded copy
                    Log.i(TAG, "$name downloaded and verified")
                }
            } catch (e: Exception) {
                Log.e(TAG, "$name install failed", e)
                part.delete() // a full disk can stop the copy part way: don't leave hundreds of megabytes behind
                fail(context, e.message ?: e.javaClass.simpleName)
            } finally {
                installing = false
            }
        }
    }

    fun delete(context: Context) {
        cancel(context)
        file(context).delete()
        failure = null
    }
}

/**
 * EmbeddingGemma 2, text + vision (440M), through LiteRT-LM: photos and searches become vectors in one space, so
 * "dog on a beach" lands near pictures of one. A 388 MB download, pinned like gillspeak's Gemma (Apache 2.0, from
 * Google's litert-community on Hugging Face). Loads on the GPU, falls back to the CPU, and is released after five
 * idle minutes.
 */
object Embedder {
    private const val IDLE_RELEASE_MS = 5 * 60_000L
    /** The model card's prefix for search queries; images take no prefix. */
    private const val QUERY = "task: search result | query: "

    val model = PinnedModel(
        "search model",
        "embeddinggemma-2-text-vision-440m.litertlm",
        387_710_976L,
        "92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0",
        "https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/" +
            "e301f74d5551b0c2641bd5cb4652a76239d5c5f8/embeddinggemma-2-text-vision-440m.litertlm",
    )

    private val lock = Any()
    private var engine: EmbeddingEngine? = null
    private var releaseTask: ScheduledFuture<*>? = null
    var backend = ""
        private set

    fun isReady(context: Context) = model.isReady(context)
    private fun prefs(context: Context) = context.getSharedPreferences("embedder", Context.MODE_PRIVATE)

    /** Image tokens per photo: 70 (faster) or 140, the two this LiteRT build has; 0 is the model's own default. */
    fun tokens(context: Context) = prefs(context).getInt("tokens", 0)
    fun setTokens(context: Context, tokens: Int) = prefs(context).edit().putInt("tokens", tokens).apply()

    fun delete(context: Context) {
        release()
        model.delete(context)
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
        val path = model.file(context).path
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
    private const val IDLE_RELEASE_MS = 10 * 60_000L

    val model = PinnedModel(
        "Gemma model",
        "gemma-4-E2B-it.litertlm",
        2_588_147_712L,
        "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
            "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm",
    )

    class Reply(val text: String, val ms: Long, val loadMs: Long)

    private val lock = Any()
    private var engine: Engine? = null
    private var releaseTask: ScheduledFuture<*>? = null

    fun delete(context: Context) {
        synchronized(lock) {
            releaseTask?.cancel(false)
            engine?.close()
            engine = null
        }
        model.delete(context)
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
        val path = model.file(context).path
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
