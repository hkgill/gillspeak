package dev.hkgill.gillspeak.wear

import java.util.concurrent.atomic.AtomicBoolean

/** Bound one native generation at a time; keep its slot until the worker actually exits. */
class RequestGate {
    class Ticket(val node: String, val id: String, val deadline: Long) {
        val cancelled = AtomicBoolean(false)
        private val terminalClaimed = AtomicBoolean(false)
        fun stopped(now: Long) = cancelled.get() || now >= deadline

        /** Claim the terminal reply before transport; cancellation checks alone are not atomic with sending. */
        fun sendReply(kind: Protocol.Kind, deliver: () -> Unit) {
            when (kind) {
                Protocol.Kind.STATUS -> if (!cancelled.get() && !terminalClaimed.get()) deliver()
                Protocol.Kind.RESULT, Protocol.Kind.ERROR ->
                    if (!cancelled.get() && terminalClaimed.compareAndSet(false, true)) deliver()
                else -> error("Only response packets can be sent.")
            }
        }

        /** Timeout, user cancellation and shutdown compete with completion for the same terminal reply. */
        fun cancelAndReply(deliver: () -> Unit) {
            if (terminalClaimed.compareAndSet(false, true)) {
                cancelled.set(true)
                deliver()
            }
        }
    }
    enum class Rejection { BUSY, DUPLICATE }
    data class Admission(val ticket: Ticket? = null, val rejection: Rejection? = null)
    private val seen = LinkedHashSet<Pair<String, String>>()
    private var active: Ticket? = null

    @Synchronized fun begin(node: String, id: String, now: Long): Admission {
        if (node to id in seen) return Admission(rejection = Rejection.DUPLICATE)
        if (active != null) return Admission(rejection = Rejection.BUSY)
        remember(node, id)
        return Admission(ticket = Ticket(node, id, now + Protocol.TIMEOUT_MS).also { active = it })
    }
    @Synchronized fun cancel(node: String, id: String): Ticket? {
        // A cancellation can beat its request over the transport: remember the tombstone too.
        remember(node, id)
        return active?.takeIf { it.node == node && it.id == id }?.also { it.cancelled.set(true) }
    }
    @Synchronized fun stop(): Ticket? = active?.also { it.cancelled.set(true) }
    @Synchronized fun finish(ticket: Ticket) { if (active === ticket) active = null }
    private fun remember(node: String, id: String) {
        seen += node to id
        while (seen.size > 128) seen.remove(seen.first())
    }
}

/** Watch-side correlation: never display a late answer or one from another phone/request. */
class PendingRequest(val node: String, val id: String, val deadline: Long) {
    var done = false
        private set
    fun accept(source: String, p: Protocol.Packet, now: Long): Boolean {
        if (done || now >= deadline || source != node || p.id != id) return false
        if (p.kind !in setOf(Protocol.Kind.STATUS, Protocol.Kind.RESULT, Protocol.Kind.ERROR)) return false
        if (p.kind != Protocol.Kind.STATUS) done = true
        return true
    }
    fun cancel() { done = true }
}
