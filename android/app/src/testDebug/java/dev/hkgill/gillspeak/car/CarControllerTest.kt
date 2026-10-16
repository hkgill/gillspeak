package dev.hkgill.gillspeak.car

import dev.hkgill.gillspeak.Recorder
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class CarControllerTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private class Harness(main: Executor = Executor { it.run() }) {
        val worker = Queue()
        var starts = 0
        var stops = 0
        var reads = 0
        var transcribes = 0
        var polishes = 0
        var cancellations = 0
        var wav = byteArrayOf()
        var problem: String? = null
        var read: (() -> Int)? = null
        var onTranscribe: () -> Unit = {}
        var onPolish: () -> Unit = {}
        var text = "Synthetic transcript."
        var silent = false
        var interrupt: () -> Unit = {}
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val states = mutableListOf<CarState>()
        val controller = CarController(worker, main, { interrupted ->
            interrupt = interrupted
            object : CarAudioInput {
                override fun start() { starts++ }
                override fun read(buffer: ByteArray): Int {
                    reads++
                    buffer.fill(if (silent) 0 else 1)
                    return read?.invoke() ?: buffer.size
                }
                override fun stop() { stops++ }
            }
        }, object : CarInference {
            override fun transcribe(wav: ByteArray): String {
                transcribes++
                this@Harness.wav = wav
                onTranscribe()
                return text
            }
            override fun polish(text: String, owner: Any, cancelled: () -> Boolean): CarReply {
                polishes++
                onPolish()
                return CarReply(text, "Offline fixture")
            }
            override fun cancel(owner: Any) { cancellations++ }
        }, { problem }, { delay, action ->
            val timer = delay to action
            timers.add(timer)
            val remove = { timers.remove(timer); Unit }
            remove
        }, { states.add(it) })
    }

    @Test fun openAndReconnectNeverCaptureOrInfer() {
        val h = Harness()
        h.controller.disconnect()
        h.controller.reconnect()
        h.worker.drain()
        assertEquals(0, h.starts)
        assertEquals(0, h.transcribes)
        assertEquals(CarPhase.READY, h.controller.state.phase)
    }

    @Test fun missingModelPermissionAndOldHostBlockBeforeOpeningMicrophone() {
        for (problem in listOf("Model missing; set up when parked", "Permission missing", "Host needs API 5")) {
            val h = Harness()
            h.problem = problem
            h.controller.speak()
            h.worker.drain()
            assertEquals(CarState(CarPhase.ERROR, problem), h.controller.state)
            assertEquals(0, h.starts)
        }
    }

    @Test fun explicitCaptureIsBoundedMono16kWavAndNoDuplicateTapQueuesWork() {
        val h = Harness()
        h.controller.speak()
        h.controller.speak()
        assertEquals(1, h.worker.tasks.size)
        h.worker.drain()
        assertEquals(1, h.starts)
        assertEquals(1, h.transcribes)
        assertEquals(1, h.polishes)
        assertEquals(CarController.MAX_PCM_BYTES + 44, h.wav.size)
        assertEquals(15_000L, Recorder.durationMs(h.wav))
        assertEquals(CarPhase.RESULT, h.controller.state.phase)
        assertTrue(h.timers.isEmpty())
        assertTrue(h.stops > 0)
    }

    @Test fun cancelBeforeWorkerStartsNeverStartsSource() {
        val h = Harness()
        h.controller.speak()
        h.controller.cancel()
        h.worker.drain()
        assertEquals(0, h.starts)
        assertEquals(0, h.transcribes)
        assertEquals(CarPhase.READY, h.controller.state.phase)
    }

    @Test fun hostDismissalDiscardsPartialAudioInsteadOfTranscribing() {
        val h = Harness()
        h.read = { if (h.reads > 20) -1 else 512 }
        h.controller.speak()
        h.worker.drain()
        assertEquals(0, h.transcribes)
        assertEquals(CarPhase.ERROR, h.controller.state.phase)
        assertTrue(h.controller.state.message.contains("discarded"))
    }

    @Test fun explicitFinishAndCaptureDeadlineAllowPartialAudio() {
        for (timeout in listOf(false, true)) {
            val h = Harness()
            h.read = {
                if (h.reads == 22) {
                    if (timeout) h.timers.first { it.first == 15_000L }.second() else h.controller.finish()
                    -1
                } else 512
            }
            h.controller.speak()
            h.worker.drain()
            assertEquals(1, h.transcribes)
            assertEquals(512 * 21 + 44, h.wav.size)
            assertEquals(CarPhase.RESULT, h.controller.state.phase)
        }
    }

    @Test fun cancelWhileListeningDiscardsAndStops() {
        val h = Harness()
        h.read = { h.controller.cancel(); 512 }
        h.controller.speak()
        h.worker.drain()
        assertEquals(0, h.transcribes)
        assertEquals(1, h.cancellations)
        assertTrue(h.stops > 0)
        assertEquals(CarPhase.READY, h.controller.state.phase)
    }

    @Test fun disconnectAndReconnectDuringAsrRejectStaleResultAndNewWork() {
        val h = Harness()
        h.onTranscribe = {
            h.controller.disconnect()
            h.controller.reconnect()
            h.controller.speak()
            assertEquals(CarPhase.STOPPING, h.controller.state.phase)
        }
        h.controller.speak()
        h.worker.drain()
        assertEquals(1, h.starts)
        assertEquals(0, h.polishes)
        assertTrue(h.states.none { it.phase == CarPhase.RESULT })
        assertEquals(CarPhase.READY, h.controller.state.phase)
        h.onTranscribe = {}
        h.controller.speak()
        h.worker.drain()
        assertEquals(2, h.starts)
        assertEquals(CarPhase.RESULT, h.controller.state.phase)
    }

    @Test fun cancelDuringGemmaOrProcessingDeadlineSuppressesReply() {
        for (timeout in listOf(false, true)) {
            val h = Harness()
            h.onPolish = {
                if (timeout) h.timers.first { it.first == 60_000L }.second() else h.controller.cancel()
            }
            h.controller.speak()
            h.worker.drain()
            assertEquals(1, h.cancellations)
            assertTrue(h.states.none { it.phase == CarPhase.RESULT })
            assertTrue(h.timers.isEmpty())
        }
    }

    @Test fun sourceFailureAndEmptyTranscriptRecoverWithoutRetrying() {
        for (sourceFails in listOf(false, true)) {
            val h = Harness()
            if (sourceFails) h.read = { throw IllegalStateException("private host error") } else h.text = ""
            h.controller.speak()
            h.worker.drain()
            assertEquals(CarPhase.ERROR, h.controller.state.phase)
            assertFalse(h.controller.state.message.contains("private"))
            assertEquals(0, h.polishes)
            h.read = null
            h.text = "Recovered fixture"
            h.controller.speak()
            h.worker.drain()
            assertEquals(CarPhase.RESULT, h.controller.state.phase)
        }
    }

    @Test fun emptyReadsAndMalformedFramesStopWithoutSpinningOrInference() {
        for (size in listOf(0, 513, 3)) {
            val h = Harness()
            h.read = { size }
            h.controller.speak()
            h.worker.drain()
            assertEquals(CarPhase.ERROR, h.controller.state.phase)
            assertEquals(0, h.transcribes)
            assertTrue(h.reads <= 64)
        }
    }

    @Test fun silentAudioIsRejectedBeforeAsr() {
        val h = Harness()
        h.silent = true
        h.controller.speak()
        h.worker.drain()
        assertEquals(CarPhase.ERROR, h.controller.state.phase)
        assertEquals(0, h.transcribes)
    }

    @Test fun staleFocusLossAndTimerCannotStopTheNextRequest() {
        val h = Harness()
        h.controller.speak()
        val oldTimer = h.timers.single().second
        h.worker.drain()
        val oldFocusLoss = h.interrupt
        h.controller.speak()
        oldTimer()
        oldFocusLoss()
        assertEquals(CarPhase.LISTENING, h.controller.state.phase)
        h.worker.drain()
        assertEquals(2, h.transcribes)
        assertEquals(CarPhase.RESULT, h.controller.state.phase)
    }

    @Test fun focusLossStopsCaptureAndPreventsInference() {
        val h = Harness()
        h.read = { h.interrupt(); 512 }
        h.controller.speak()
        h.worker.drain()
        assertEquals(0, h.transcribes)
        assertEquals(1, h.cancellations)
    }

    @Test fun queuedResultIsSuppressedAfterDisconnect() {
        val main = Queue()
        val h = Harness(main)
        h.controller.speak()
        h.worker.drain()
        h.controller.disconnect()
        main.drain()
        assertEquals(CarPhase.DISCONNECTED, h.controller.state.phase)
        assertTrue(h.states.none { it.phase == CarPhase.RESULT })
    }
}
