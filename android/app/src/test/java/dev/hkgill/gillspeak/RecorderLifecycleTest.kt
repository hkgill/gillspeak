package dev.hkgill.gillspeak

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RecorderLifecycleTest {
    private class BlockingCapture : MicCapture {
        val reading = CountDownLatch(1)
        private val stopped = CountDownLatch(1)
        @Volatile var closed = false
        override fun prefer(device: MicDevice?) = true
        override fun start() {}
        override fun actual() = sco.copy(input = true)
        override fun read(buffer: ByteArray): Int {
            reading.countDown()
            check(stopped.await(2, TimeUnit.SECONDS))
            return -3
        }
        override fun stop() { stopped.countDown() }
        override fun close() { closed = true }
    }

    @Test fun cancelOrServiceShutdownUnblocksReadAndReleasesAllResources() {
        val p = FakeRouting()
        val c = BlockingCapture()
        val r = Recorder({ p }, FakeRouteClock()) { _, _ -> c }
        try {
            r.start()
            assertTrue(c.reading.await(2, TimeUnit.SECONDS))
            r.cancel() // the same path used by controller.shutdown and hidden/cancelled UI surfaces
            assertFalse(r.isRecording)
            assertFalse(p.active)
            assertTrue(c.closed)
            assertNull(r.error) // stop-induced read errors are cancellation, not capture failures
        } finally { r.cancel() }
    }

    @Test fun nativeReadFailureAutomaticallyReleasesRoutesBeforeControllerPolls() {
        val p = FakeRouting()
        val c = object : MicCapture {
            var closed = false
            override fun prefer(device: MicDevice?) = true
            override fun start() {}
            override fun actual() = sco.copy(input = true)
            override fun read(buffer: ByteArray) = -6 // ERROR_DEAD_OBJECT
            override fun stop() {}
            override fun close() { closed = true }
        }
        val r = Recorder({ p }, FakeRouteClock()) { _, _ -> c }
        try {
            r.start()
            awaitStopped(r)
            assertEquals(-6, r.error)
            assertFalse(p.active)
            assertTrue(c.closed)
            assertEquals(44, r.stop().size)
        } finally { r.cancel() }
    }

    @Test fun recorderStartupFailureIncludingBackendCreationReleasesOwnership() {
        for (backendFailure in listOf(false, true)) {
            val p = FakeRouting()
            val c = FakeCapture(null).apply { startError = SecurityException("denied") }
            val r = Recorder({ if (backendFailure) throw IllegalStateException("audio service unavailable") else p }, FakeRouteClock()) { _, _ -> c }
            try {
                r.start()
                awaitStopped(r)
                assertNotNull(r.failure)
                assertFalse(p.active)
                val next = Any()
                try { assertTrue(RecordingOwner.acquire(next)) } finally { RecordingOwner.release(next) }
            } finally { r.cancel() }
        }
    }

    @Test fun cancellationDuringSetupNeverStartsTheMicrophoneAndCanRestart() {
        val p = FakeRouting().apply { ready = false }
        val paused = CountDownLatch(1)
        val clock = object : RouteClock {
            override fun now() = 0L
            override fun pause() { paused.countDown(); Thread.sleep(10) }
        }
        var opens = 0
        val r = Recorder({ p }, clock) { _, _ -> opens++; FakeCapture(phone) }
        try {
            r.start()
            assertTrue(paused.await(2, TimeUnit.SECONDS))
            r.cancel()
            assertFalse(r.isRecording)
            assertEquals(0, opens)
            assertFalse(p.active)
            r.start()
            r.cancel()
            assertFalse(r.isRecording)
            assertEquals(0, opens)
        } finally { r.cancel() }
    }

    @Test fun routeChangeStopsCaptureBeforeWritingSamplesFromTheNewMicrophone() {
        val p = FakeRouting()
        val c = object : MicCapture {
            var changed = false
            var closed = false
            override fun prefer(device: MicDevice?) = true
            override fun start() {}
            override fun actual() = if (changed) phone else sco.copy(input = true)
            override fun read(buffer: ByteArray): Int { changed = true; buffer.fill(1); return buffer.size }
            override fun stop() {}
            override fun close() { closed = true }
        }
        val r = Recorder({ p }, FakeRouteClock()) { _, _ -> c }
        try {
            r.start()
            awaitStopped(r)
            assertNotNull(r.failure)
            assertFalse(r.heard)
            assertEquals(44, r.stop().size)
            assertTrue(c.closed)
            assertFalse(p.active)
        } finally { r.cancel() }
    }

    private fun awaitStopped(recorder: Recorder) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (recorder.isRecording && System.nanoTime() < until) Thread.sleep(5)
        assertFalse("capture worker did not stop", recorder.isRecording)
    }
}
