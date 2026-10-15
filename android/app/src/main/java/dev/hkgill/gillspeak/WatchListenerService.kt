package dev.hkgill.gillspeak

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.hkgill.gillspeak.wear.Protocol
import java.util.concurrent.TimeUnit

/** Play services authenticates package + certificate. Also require the nearby watch capability. */
class WatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Protocol.REQUEST_PATH) return
        val packet = Protocol.decode(event.data) ?: return
        if (packet.kind !in setOf(Protocol.Kind.ASK, Protocol.Kind.CANCEL, Protocol.Kind.PING)) return
        val nearby = runCatching {
            Tasks.await(Wearable.getCapabilityClient(this).getCapability(Protocol.WATCH, CapabilityClient.FILTER_REACHABLE),
                3, TimeUnit.SECONDS).nodes.any { it.id == event.sourceNodeId && it.isNearby }
        }.getOrDefault(false)
        if (!nearby) return
        if (!Protocol.fresh(packet, System.currentTimeMillis())) {
            reply(event.sourceNodeId, Protocol.Packet(packet.id, Protocol.Kind.ERROR, "Request expired. Check both devices' clocks and try again."))
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val session = WatchSessionService.current
            if (session == null) {
                reply(event.sourceNodeId, Protocol.Packet(packet.id, Protocol.Kind.ERROR,
                    "Open GillSpeak on your phone → Watch companion → Enable for 30 minutes."))
            } else session.receive(event.sourceNodeId, packet)
        }
    }
    private fun reply(node: String, p: Protocol.Packet) {
        Wearable.getMessageClient(this).sendMessage(node, Protocol.REPLY_PATH, Protocol.encode(p))
            .addOnFailureListener { /* Ephemeral: watch timeout handles a lost connection. */ }
    }
}
