package dev.hkgill.gillspeak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.google.android.gms.wearable.Wearable
import dev.hkgill.gillspeak.wear.AnswerTask
import dev.hkgill.gillspeak.wear.Protocol
import dev.hkgill.gillspeak.wear.RequestGate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** User-started, bounded foreground session; never starts from a remote message or opens the microphone. */
class WatchSessionService : Service() {
    companion object {
        @Volatile var current: WatchSessionService? = null
            private set
        const val STOP = "dev.hkgill.gillspeak.STOP_WATCH"
        const val SESSION_MS = 30 * 60_000L
        const val SYSTEM_PROMPT = "You are GillSpeak, a helpful assistant running locally on the user's phone. " +
            "Answer the question directly and briefly for a small watch screen, usually in 1-3 sentences. " +
            "You cannot browse, access live information, read phone data, or perform actions. " +
            "Do not claim to have done anything on the user's behalf. Be honest when unsure."
    }
    private val gate = RequestGate()
    private val worker = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val endSession = object : Runnable {
        override fun run() {
            if (!isActive) stopSelf()
            else main.postDelayed(this, minOf(30_000L, expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        }
    }
    @Volatile private var stopped = false
    @Volatile private var expiresAt = 0L
    val isActive: Boolean get() = !stopped && SystemClock.elapsedRealtime() < expiresAt

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("watch_session", "Watch companion", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, WatchSetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WatchSessionService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        startForeground(73, Notification.Builder(this, "watch_session")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("GillSpeak watch companion")
            .setContentText("Local Gemma available for 30 minutes. Tap to manage.")
            .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build())
        expiresAt = SystemClock.elapsedRealtime() + SESSION_MS
        current = this
        main.removeCallbacks(endSession)
        main.postDelayed(endSession, 30_000L)
        return START_NOT_STICKY
    }

    fun receive(node: String, packet: Protocol.Packet) {
        if (packet.kind == Protocol.Kind.CANCEL) {
            gate.cancel(node, packet.id)?.let { ticket ->
                ticket.cancelAndReply {
                    LocalLlm.cancel(ticket)
                    reply(node, packet.id, Protocol.Kind.ERROR, "Cancelled.")
                }
            }
            return
        }
        if (!isActive) {
            reply(node, packet.id, Protocol.Kind.ERROR, "Phone session ended. Enable it again on your phone.")
            stopSelf(); return
        }
        when (packet.kind) {
            Protocol.Kind.PING -> reply(node, packet.id, Protocol.Kind.RESULT,
                if (LocalLlm.isReady(this)) "Phone connected · Gemma ready" else "Phone connected · download Gemma in Speech and understanding.")
            Protocol.Kind.ASK -> ask(node, packet)
            else -> Unit
        }
    }

    private fun ask(node: String, packet: Protocol.Packet) {
        if (!LocalLlm.isReady(this)) {
            reply(node, packet.id, Protocol.Kind.ERROR, "Gemma isn't ready. Open Speech and understanding on your phone to finish its download.")
            return
        }
        val admission = gate.begin(node, packet.id, SystemClock.elapsedRealtime())
        val ticket = admission.ticket ?: run {
            reply(node, packet.id, Protocol.Kind.ERROR, if (admission.rejection == RequestGate.Rejection.BUSY)
                "Phone is finishing another watch request. Try again shortly." else "This request already ended. Send a new question.")
            return
        }
        val remaining = (expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(1)
        val timeout = timer.schedule({
            ticket.cancelAndReply {
                LocalLlm.cancel(ticket)
                reply(node, packet.id, Protocol.Kind.ERROR, if (isActive) "Phone took too long. Try a shorter question."
                    else "Phone session ended. Enable it again on your phone.")
            }
        }, minOf(Protocol.TIMEOUT_MS, remaining), TimeUnit.MILLISECONDS)
        worker.execute {
            val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gillspeak:watch-answer")
            try {
                wake.acquire(Protocol.TIMEOUT_MS + 5000)
                val cancelled = { !isActive || ticket.stopped(SystemClock.elapsedRealtime()) }
                AnswerTask.run({ LocalLlm.isReady(this) }, cancelled, { onReady ->
                    LocalLlm.ask(applicationContext, SYSTEM_PROMPT, packet.text, maxTokens = 256, owner = ticket,
                        shouldCancel = cancelled, onReady = onReady).text
                }) { kind, text -> ticket.sendReply(kind) { reply(node, packet.id, kind, text) } }
            } finally {
                timeout.cancel(false)
                if (wake.isHeld) wake.release()
                gate.finish(ticket)
            }
        }
    }

    private fun reply(node: String, id: String, kind: Protocol.Kind, text: String) {
        Wearable.getMessageClient(this).sendMessage(node, Protocol.REPLY_PATH, Protocol.encode(Protocol.Packet(id, kind, text)))
            .addOnFailureListener { /* No persisted messages or automatic resends of private questions. */ }
    }
    override fun onDestroy() {
        stopped = true
        current = null
        main.removeCallbacks(endSession)
        gate.stop()?.let { ticket ->
            ticket.cancelAndReply {
                LocalLlm.cancel(ticket)
                reply(ticket.node, ticket.id, Protocol.Kind.ERROR, "Phone session ended. Enable it again on your phone.")
            }
        }
        timer.shutdownNow()
        worker.shutdownNow()
        super.onDestroy()
    }
}
