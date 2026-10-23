package dev.hkgill.gillspeak

import org.junit.Assert.*
import org.junit.Test

internal class FakeCapture(var route: MicDevice?) : MicCapture {
    var preferred: MicDevice? = null
    var acceptsPreference = true
    var startError: Exception? = null
    var stopError: Exception? = null
    var starts = 0
    var stops = 0
    var closes = 0
    var onStart: () -> Unit = {}
    override fun prefer(device: MicDevice?): Boolean { preferred = device; return acceptsPreference }
    override fun start() { starts++; onStart(); startError?.let { throw it } }
    override fun actual() = route
    override fun read(buffer: ByteArray) = 0
    override fun stop() { stops++; stopError?.let { throw it } }
    override fun close() { closes++ }
}

class RoutedCaptureTest {
    @Test fun wirelessUsesCommunicationInputNotAnOutputPassedToPreferredDevice() {
        val p = FakeRouting()
        val c = FakeCapture(sco.copy(id = 40, input = true))
        RoutedCapture(p, FakeRouteClock()) { wireless -> assertTrue(wireless); c }.use {
            it.open { false }
            assertNull(c.preferred)
            assertFalse(it.routeLost(c.actual()))
        }
        assertEquals(1, c.closes)
        assertFalse(p.active)
    }

    @Test fun wiredAndUsbUseExactInputDeviceWithoutChangingCommunicationMode() {
        for (device in listOf(wired, usb)) {
            val p = FakeRouting().apply { inputDevices = listOf(phone, device) }
            val c = FakeCapture(device)
            RoutedCapture(p, FakeRouteClock()) { wireless -> assertFalse(wireless); c }.use {
                it.open { false }
                assertEquals(device, c.preferred)
            }
            assertEquals(0, p.requests)
            assertEquals(1, c.closes)
        }
    }

    @Test fun acceptedRequestStillRequiresAnActualHeadsetInputAfterStart() {
        val p = FakeRouting()
        val clock = FakeRouteClock()
        val headset = FakeCapture(phone) // Android ignored the requested route
        val fallback = FakeCapture(phone)
        var opens = 0
        RoutedCapture(p, clock) { if (opens++ == 0) headset else fallback }.use {
            it.open { false }
            assertEquals(RoutedCapture.CONFIRM_TIMEOUT_MS, clock.time)
            assertEquals(phone, it.choice.device)
            assertNotNull(it.choice.reason)
            assertEquals(1, headset.closes)
            assertFalse(p.active)
        }
        assertEquals(1, fallback.closes)
    }

    @Test fun waitsForActualRouteNotificationAfterCaptureStarts() {
        val p = FakeRouting()
        val clock = FakeRouteClock()
        val c = FakeCapture(null)
        clock.onPause = { if (clock.time == 100L) c.route = sco.copy(input = true) }
        RoutedCapture(p, clock) { c }.use {
            it.open { false }
            assertEquals(100L, clock.time)
        }
    }

    @Test fun failedHeadsetStartIsReleasedBeforeOpeningPhone() {
        val p = FakeRouting()
        val headset = FakeCapture(null).apply { startError = IllegalStateException("failed") }
        val fallback = FakeCapture(phone)
        var opens = 0
        RoutedCapture(p, FakeRouteClock()) {
            if (opens++ == 0) headset else {
                assertFalse(p.active)
                assertEquals(1, headset.closes)
                fallback
            }
        }.use { it.open { false } }
        assertEquals(2, opens)
        assertEquals(1, fallback.closes)
    }

    @Test fun rejectedWiredPreferenceFallsBackAndReleasesBothRecords() {
        val p = FakeRouting().apply { inputDevices = listOf(phone, wired) }
        val headset = FakeCapture(wired).apply { acceptsPreference = false }
        val fallback = FakeCapture(phone)
        var opens = 0
        RoutedCapture(p, FakeRouteClock()) { if (opens++ == 0) headset else fallback }.use {
            it.open { false }
            assertEquals(phone, fallback.preferred)
            assertEquals(0, headset.starts)
        }
        assertEquals(1, headset.closes)
        assertEquals(1, fallback.closes)
    }

    @Test fun microphonePermissionDenialDoesNotRetryCaptureAndReleasesRouting() {
        val p = FakeRouting()
        val c = FakeCapture(null).apply { startError = SecurityException("RECORD_AUDIO denied") }
        val r = RoutedCapture(p, FakeRouteClock()) { c }
        assertThrows(SecurityException::class.java) { r.open { false } }
        r.close()
        assertEquals(1, c.starts)
        assertEquals(1, c.closes)
        assertEquals(1, p.releases)
    }

    @Test fun failedConstructorReleasesRouting() {
        val p = FakeRouting()
        val r = RoutedCapture(p, FakeRouteClock()) { throw SecurityException("denied") }
        assertThrows(SecurityException::class.java) { r.open { false } }
        assertFalse(p.active)
    }

    @Test fun cancellationDuringRouteSetupDoesNotConstructCapture() {
        val p = FakeRouting().apply { ready = false }
        val clock = FakeRouteClock()
        var opens = 0
        val r = RoutedCapture(p, clock) { opens++; FakeCapture(phone) }
        assertThrows(RouteCancelled::class.java) { r.open { clock.time >= 100 } }
        assertEquals(0, opens)
        assertFalse(p.active)
    }

    @Test fun shutdownDuringCaptureConfirmationReleasesCaptureAndRoutingExactlyOnce() {
        val p = FakeRouting()
        val clock = FakeRouteClock()
        val c = FakeCapture(null)
        val r = RoutedCapture(p, clock) { c }
        assertThrows(RouteCancelled::class.java) { r.open { clock.time >= 100 } }
        r.close()
        assertEquals(1, c.stops)
        assertEquals(1, c.closes)
        assertEquals(1, p.releases)
    }

    @Test fun headsetDisconnectOrSwitchIsDetectedAndCleanupIsIdempotent() {
        val p = FakeRouting()
        val c = FakeCapture(sco.copy(input = true))
        val r = RoutedCapture(p, FakeRouteClock()) { c }
        r.open { false }
        assertTrue(r.routeLost(phone))
        assertTrue(r.routeLost(null))
        p.ready = false
        assertTrue(r.routeLost(c.actual()))
        r.close()
        r.close()
        assertEquals(1, c.closes)
        assertEquals(1, p.releases)
    }

    @Test fun failingStopStillReleasesNativeCaptureAndCommunication() {
        val p = FakeRouting()
        val c = FakeCapture(sco.copy(input = true)).apply { stopError = IllegalStateException("dead") }
        val r = RoutedCapture(p, FakeRouteClock()) { c }
        r.open { false }
        assertThrows(IllegalStateException::class.java) { r.close() }
        assertEquals(1, c.closes)
        assertFalse(p.active)
    }

    @Test fun defaultInputIsAlsoMonitoredWhenThePhoneEndpointWasNotEnumerated() {
        val p = FakeRouting().apply { inputDevices = emptyList(); communicationDevices = emptyList() }
        val c = FakeCapture(usb)
        RoutedCapture(p, FakeRouteClock()) { c }.use {
            it.open { false }
            assertFalse(it.routeLost(usb))
            assertTrue(it.routeLost(phone))
            assertTrue(it.routeLost(null))
        }
    }

    @Test fun differentActivationSurfaceCannotAcquireOrReleaseAnotherRecordingsLease() {
        val first = Any()
        val second = Any()
        try {
            assertTrue(RecordingOwner.acquire(first))
            assertFalse(RecordingOwner.acquire(second))
            RecordingOwner.release(second)
            assertFalse(RecordingOwner.acquire(second))
            RecordingOwner.release(first)
            assertTrue(RecordingOwner.acquire(second))
        } finally {
            RecordingOwner.release(first)
            RecordingOwner.release(second)
        }
    }
}
