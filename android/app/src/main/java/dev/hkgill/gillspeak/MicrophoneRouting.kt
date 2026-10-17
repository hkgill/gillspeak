package dev.hkgill.gillspeak

/** No names, addresses or Bluetooth discovery: only audio endpoints exposed by Android. */
internal enum class MicKind(val label: String, val wireless: Boolean = false) {
    PHONE("Phone mic"), WIRED("Wired headset mic"), USB("USB mic"),
    SCO("Bluetooth mic", true), BLE("Bluetooth LE mic", true), OTHER("External mic"),
    OUTPUT_ONLY("Headphones"),
}

internal data class MicDevice(val id: Int, val kind: MicKind, val input: Boolean)
internal data class MicChoice(val device: MicDevice?, val reason: String? = null) {
    val wireless get() = device?.kind?.wireless == true
}

/** A wired/USB input wins; otherwise keep the current wireless route, then prefer LE over classic HFP. */
internal fun chooseMicrophone(inputs: List<MicDevice>, communications: List<MicDevice>, current: Int?): MicDevice? =
    inputs.filter { it.input && it.kind in listOf(MicKind.WIRED, MicKind.USB) }
        .sortedWith(compareBy<MicDevice> { if (it.kind == MicKind.WIRED) 0 else 1 }.thenBy { it.id }).firstOrNull()
        ?: communications.filter { it.kind.wireless }
            .sortedWith(compareBy<MicDevice> { if (it.id == current) 0 else 1 }
                .thenBy { if (it.kind == MicKind.BLE) 0 else 1 }.thenBy { it.id }).firstOrNull()
        ?: inputs.firstOrNull { it.input && it.kind == MicKind.PHONE }

internal interface RoutingPlatform {
    fun inputs(): List<MicDevice>
    fun outputs(): List<MicDevice>
    fun communications(): List<MicDevice>
    fun currentCommunication(): Int?
    fun requestCommunication(device: MicDevice): Boolean
    fun communicationReady(device: MicDevice): Boolean
    /** Release only requests made by this session. Must attempt every cleanup even if one fails. */
    fun releaseCommunication()
}

internal interface RouteClock {
    fun now(): Long
    fun pause()
}

internal class RouteCancelled : Exception()

/** Blocking only on the capture thread. Cancellation never opens a microphone after setup. */
internal class MicrophoneRouting(private val platform: RoutingPlatform, private val clock: RouteClock) : AutoCloseable {
    private var closed = false

    fun prepare(cancelled: () -> Boolean): MicChoice {
        check(!closed)
        checkCancelled(cancelled)
        try {
            val inputs = platform.inputs()
            val selected = chooseMicrophone(inputs, platform.communications(), platform.currentCommunication())
            if (selected?.kind?.wireless != true) {
                val headphones = platform.outputs().any {
                    it.kind in listOf(MicKind.OUTPUT_ONLY, MicKind.WIRED, MicKind.USB, MicKind.SCO, MicKind.BLE)
                }
                return MicChoice(selected, if (headphones && selected?.kind == MicKind.PHONE)
                    "headset microphone profile unavailable" else null)
            }
            if (!platform.requestCommunication(selected)) return fallback("headset route unavailable")
            val until = clock.now() + SETUP_TIMEOUT_MS
            while (true) {
                checkCancelled(cancelled)
                if (platform.communicationReady(selected)) return MicChoice(selected)
                if (clock.now() >= until) return fallback("headset connection timed out")
                clock.pause()
            }
        } catch (e: RouteCancelled) {
            close()
            throw e
        } catch (e: SecurityException) {
            return fallback("headset routing permission denied")
        } catch (e: RuntimeException) {
            return fallback("headset route unavailable")
        }
    }

    fun fallback(reason: String): MicChoice {
        platform.releaseCommunication()
        return MicChoice(runCatching { platform.inputs().firstOrNull { it.kind == MicKind.PHONE && it.input } }.getOrNull(), reason)
    }

    override fun close() {
        if (closed) return
        closed = true
        platform.releaseCommunication()
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw RouteCancelled()
    }

    companion object { const val SETUP_TIMEOUT_MS = 5_000L }
}

/** A requested device is never presented as the active input. Null means Android hasn't confirmed it. */
internal fun microphoneStatus(actual: MicDevice?, reason: String?): String = when {
    actual == null -> "Microphone route unconfirmed"
    actual.kind == MicKind.PHONE && reason != null -> "Phone mic · $reason"
    else -> actual.kind.label
}
