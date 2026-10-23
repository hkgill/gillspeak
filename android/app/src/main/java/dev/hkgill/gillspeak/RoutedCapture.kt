package dev.hkgill.gillspeak

/** Small capture boundary so startup/route failures can be exercised without opening a real microphone. */
internal interface MicCapture : AutoCloseable {
    fun prefer(device: MicDevice?): Boolean
    fun start()
    fun actual(): MicDevice?
    fun read(buffer: ByteArray): Int
    fun stop()
}

internal class RoutedCapture(
    private val platform: RoutingPlatform,
    private val clock: RouteClock,
    private val create: (wireless: Boolean) -> MicCapture,
) : AutoCloseable {
    private val routing = MicrophoneRouting(platform, clock)
    var capture: MicCapture? = null
        private set
    var choice = MicChoice(null)
        private set
    private var confirmedInput: MicDevice? = null

    fun open(cancelled: () -> Boolean) {
        try {
            choice = routing.prepare(cancelled)
            try {
                openChoice(cancelled)
            } catch (e: RouteCancelled) {
                throw e
            } catch (e: SecurityException) {
                // RECORD_AUDIO was revoked: changing the input cannot grant access.
                throw e
            } catch (e: Exception) {
                if (choice.device?.kind !in listOf(MicKind.WIRED, MicKind.USB, MicKind.SCO, MicKind.BLE)) throw e
                releaseCapture()
                choice = routing.fallback("headset microphone unavailable")
                openChoice(cancelled)
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun openChoice(cancelled: () -> Boolean) {
        checkCancelled(cancelled)
        val c = create(choice.wireless)
        capture = c // owned before any operation that can throw
        if (!choice.wireless && !c.prefer(choice.device)) throw IllegalStateException("input selection rejected")
        checkCancelled(cancelled)
        c.start()
        val until = clock.now() + CONFIRM_TIMEOUT_MS
        while (true) {
            checkCancelled(cancelled)
            val actual = c.actual()
            val target = choice.device
            if (actual != null && when {
                target == null -> true
                choice.wireless -> actual.kind == target.kind && platform.communicationReady(target)
                else -> actual.id == target.id
            }) {
                confirmedInput = actual
                return
            }
            if (clock.now() >= until) throw IllegalStateException("microphone route unconfirmed")
            clock.pause()
        }
    }

    /** Stop on a mid-session route change; do not silently record through a different microphone. */
    fun routeLost(actual: MicDevice?): Boolean {
        if (actual?.id != confirmedInput?.id) return true
        val target = choice.device
        return choice.wireless && target != null && !platform.communicationReady(target)
    }

    private fun releaseCapture() {
        val c = capture ?: return
        capture = null
        try { c.stop() } finally { c.close() }
    }

    override fun close() {
        try { releaseCapture() } finally { routing.close() }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw RouteCancelled()
    }

    companion object { const val CONFIRM_TIMEOUT_MS = 1_500L }
}
