package dev.hkgill.gillspeak

import org.junit.Assert.*
import org.junit.Test

internal val phone = MicDevice(1, MicKind.PHONE, true)
internal val wired = MicDevice(2, MicKind.WIRED, true)
internal val usb = MicDevice(3, MicKind.USB, true)
internal val sco = MicDevice(4, MicKind.SCO, false)
internal val ble = MicDevice(5, MicKind.BLE, false)

internal class FakeRouteClock : RouteClock {
    var time = 0L
    var onPause: () -> Unit = {}
    override fun now() = time
    override fun pause() { time += 50; onPause() }
}

internal class FakeRouting : RoutingPlatform {
    var inputDevices = listOf(phone)
    var outputDevices = emptyList<MicDevice>()
    var communicationDevices = listOf(sco)
    var current: Int? = null
    var accepted = true
    var ready = true
    var permissionDenied = false
    var requests = 0
    var releases = 0
    var active = false
    override fun inputs() = inputDevices
    override fun outputs() = outputDevices
    override fun communications() = communicationDevices
    override fun currentCommunication() = current
    override fun requestCommunication(device: MicDevice): Boolean {
        requests++
        active = true
        if (permissionDenied) throw SecurityException("denied")
        return accepted
    }
    override fun communicationReady(device: MicDevice) = ready
    override fun releaseCommunication() { if (active) releases++; active = false }
}

class MicrophoneRoutingTest {
    @Test fun wiredAndUsbInputsBeatWireless() {
        assertEquals(wired, chooseMicrophone(listOf(phone, usb, wired), listOf(sco, ble), ble.id))
        assertEquals(usb, chooseMicrophone(listOf(phone, usb), listOf(sco), sco.id))
    }

    @Test fun outputOnlyWiredOrUsbDevicesAreNeverSelectedAsInputs() {
        assertEquals(phone, chooseMicrophone(listOf(phone, wired.copy(input = false), usb.copy(input = false)), emptyList(), null))
    }

    @Test fun keepsCurrentWirelessOtherwisePrefersBleThenHfp() {
        assertEquals(sco, chooseMicrophone(listOf(phone), listOf(ble, sco), sco.id))
        assertEquals(ble, chooseMicrophone(listOf(phone), listOf(sco, ble), null))
        assertEquals(sco, chooseMicrophone(listOf(phone), listOf(sco), null))
    }

    @Test fun a2dpAndPlaybackOnlyBleCannotMasqueradeAsMicrophones() {
        val p = FakeRouting().apply {
            communicationDevices = listOf(MicDevice(7, MicKind.OUTPUT_ONLY, false))
            outputDevices = communicationDevices
        }
        MicrophoneRouting(p, FakeRouteClock()).use {
            val choice = it.prepare { false }
            assertEquals(phone, choice.device)
            assertEquals("headset microphone profile unavailable", choice.reason)
            assertEquals(0, p.requests)
        }
    }

    @Test fun wiredOrUsbOutputWithoutAnInputExplainsThePhoneFallback() {
        for (kind in listOf(MicKind.WIRED, MicKind.USB)) {
            val p = FakeRouting().apply {
                communicationDevices = emptyList()
                outputDevices = listOf(MicDevice(7, kind, false))
            }
            MicrophoneRouting(p, FakeRouteClock()).use {
                val choice = it.prepare { false }
                assertEquals(phone, choice.device)
                assertNotNull(choice.reason)
            }
        }
    }

    @Test fun waitsForBluetoothInsteadOfTreatingAnAcceptedRequestAsReady() {
        val p = FakeRouting().apply { ready = false }
        val clock = FakeRouteClock().apply { onPause = { if (time >= 200) p.ready = true } }
        MicrophoneRouting(p, clock).use {
            assertEquals(sco, it.prepare { false }.device)
            assertEquals(200L, clock.time)
            assertTrue(p.active)
        }
        assertFalse(p.active)
        assertEquals(1, p.releases)
    }

    @Test fun timeoutReleasesCommunicationAndSelectsPhone() {
        val p = FakeRouting().apply { ready = false }
        val clock = FakeRouteClock()
        MicrophoneRouting(p, clock).use {
            val choice = it.prepare { false }
            assertEquals(phone, choice.device)
            assertEquals("headset connection timed out", choice.reason)
            assertEquals(MicrophoneRouting.SETUP_TIMEOUT_MS, clock.time)
            assertFalse(p.active)
        }
        assertEquals(1, p.releases)
    }

    @Test fun rejectedRequestAndDeniedRoutingPermissionCleanUpBeforePhoneFallback() {
        for (denied in listOf(false, true)) {
            val p = FakeRouting().apply { accepted = false; permissionDenied = denied }
            MicrophoneRouting(p, FakeRouteClock()).use {
                assertEquals(phone, it.prepare { false }.device)
                assertFalse(p.active)
            }
            assertEquals(1, p.releases)
        }
    }

    @Test fun cancellationBeforeAndDuringSetupNeverReturnsACaptureChoice() {
        for (before in listOf(false, true)) {
            val p = FakeRouting().apply { ready = false }
            val clock = FakeRouteClock()
            val r = MicrophoneRouting(p, clock)
            assertThrows(RouteCancelled::class.java) { r.prepare { before || clock.time >= 100 } }
            r.close()
            assertFalse(p.active)
            assertEquals(if (before) 0 else 1, p.requests)
            assertEquals(if (before) 0 else 1, p.releases)
        }
    }

    @Test fun statusUsesConfirmedInputAndDoesNotInventPhoneFallback() {
        assertEquals("Microphone route unconfirmed", microphoneStatus(null, "headset route unavailable"))
        assertEquals("Phone mic · headset route unavailable", microphoneStatus(phone, "headset route unavailable"))
        assertEquals("USB mic", microphoneStatus(usb, "headset route unavailable"))
    }
}
