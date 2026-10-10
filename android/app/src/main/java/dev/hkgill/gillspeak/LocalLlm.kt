package dev.hkgill.gillspeak

import android.content.Context
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
 * The model (2.6 GB) holds 2-3 GB of memory while loaded, so it loads on first use and is released after a couple
 * of idle minutes. Calls are serialised: one conversation at a time.
 */
object LocalLlm {
    const val FILE = "gemma-4-E2B-it.litertlm"
    const val SIZE = 2_588_147_712L
    const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    private const val IDLE_RELEASE_MS = 2 * 60_000L

    data class Reply(val text: String, val ms: Long, val loadMs: Long)

    private var engine: Engine? = null
    private var backend = "" // what the loaded engine runs on, for the log
    private val lock = Any()
    private val background = Executors.newSingleThreadScheduledExecutor()
    private var releaseTask: ScheduledFuture<*>? = null

    fun file(context: Context) = File(context.filesDir, "models/$FILE")

    fun isReady(context: Context) = file(context).length() == SIZE

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
        scheduleRelease()
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
