package dev.hkgill.gillspeak.wear

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TerminalReplyTest {
    @Test fun timeoutBetweenCancellationCheckAndResultDeliveryWins() {
        val ticket = RequestGate().begin("watch", "request", 0).ticket!!
        val out = Collections.synchronizedList(mutableListOf<Protocol.Kind>())
        val checked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        var generated = false
        val task = worker.submit {
            AnswerTask.run({ true }, {
                val cancelled = ticket.stopped(0)
                if (generated) {
                    // Freeze after AnswerTask observes false, before it invokes the result callback.
                    checked.countDown()
                    assertTrue(resume.await(5, TimeUnit.SECONDS))
                }
                cancelled
            }, { generated = true; "answer" }) { kind, _ ->
                ticket.sendReply(kind) { out += kind }
            }
        }
        try {
            assertTrue(checked.await(5, TimeUnit.SECONDS))
            ticket.cancelAndReply { out += Protocol.Kind.ERROR }
            assertTrue(ticket.cancelled.get())
            resume.countDown()
            task.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(Protocol.Kind.ERROR), out.filter { it != Protocol.Kind.STATUS })
        } finally {
            resume.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun deliveredResultBeatsTimeoutBeforeWorkerCleanup() {
        val ticket = RequestGate().begin("watch", "request", 0).ticket!!
        val out = Collections.synchronizedList(mutableListOf<Protocol.Kind>())
        val delivered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val task = worker.submit {
            AnswerTask.run({ true }, { ticket.stopped(0) }, { "answer" }) { kind, _ ->
                ticket.sendReply(kind) {
                    out += kind
                    if (kind == Protocol.Kind.RESULT) {
                        // The result is sent, but the worker has not reached its timeout.cancel(false) cleanup.
                        delivered.countDown()
                        assertTrue(resume.await(5, TimeUnit.SECONDS))
                    }
                }
            }
        }
        try {
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            ticket.cancelAndReply { out += Protocol.Kind.ERROR }
            assertFalse(ticket.cancelled.get())
            resume.countDown()
            task.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(Protocol.Kind.RESULT), out.filter { it != Protocol.Kind.STATUS })
        } finally {
            resume.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun inferenceFailureBeatsLaterTimeout() {
        val ticket = RequestGate().begin("watch", "request", 0).ticket!!
        val out = mutableListOf<String>()
        AnswerTask.run({ true }, { ticket.stopped(0) }, { throw IllegalStateException("Synthetic failure") }) { kind, text ->
            ticket.sendReply(kind) { if (kind != Protocol.Kind.STATUS) out += text }
        }
        ticket.cancelAndReply { out += "timeout" }
        assertEquals(1, out.size)
        assertTrue(out.single().startsWith("Gemma couldn't answer."))
        assertFalse(ticket.cancelled.get())
    }

    @Test fun userCancellationSuppressesLateProgressAndCompletion() {
        val gate = RequestGate()
        val ticket = gate.begin("watch", "request", 0).ticket!!
        val out = mutableListOf<Protocol.Kind>()
        gate.cancel("watch", "request")!!.cancelAndReply { out += Protocol.Kind.ERROR }
        ticket.sendReply(Protocol.Kind.STATUS) { out += Protocol.Kind.STATUS }
        ticket.sendReply(Protocol.Kind.RESULT) { out += Protocol.Kind.RESULT }
        ticket.cancelAndReply { out += Protocol.Kind.ERROR }
        assertEquals(listOf(Protocol.Kind.ERROR), out)
    }

    @Test fun shutdownWinsOnceAndKeepsSlotUntilWorkerExits() {
        val gate = RequestGate()
        val ticket = gate.begin("watch", "request", 0).ticket!!
        val out = mutableListOf<String>()
        gate.stop()!!.cancelAndReply { out += "shutdown" }
        gate.cancel("watch", "request")!!.cancelAndReply { out += "cancelled" }
        ticket.sendReply(Protocol.Kind.ERROR) { out += "inference failure" }
        ticket.cancelAndReply { out += "timeout" }
        assertEquals(listOf("shutdown"), out)
        assertEquals(RequestGate.Rejection.BUSY, gate.begin("watch", "next", 1).rejection)
        gate.finish(ticket)
        assertNotNull(gate.begin("watch", "next", 2).ticket)
    }

    @Test fun completionSuppressesCancellationAndShutdownRepliesBeforeCleanup() {
        val gate = RequestGate()
        val ticket = gate.begin("watch", "request", 0).ticket!!
        val out = mutableListOf<Protocol.Kind>()
        AnswerTask.run({ true }, { ticket.stopped(0) }, { "answer" }) { kind, _ ->
            ticket.sendReply(kind) { if (kind != Protocol.Kind.STATUS) out += kind }
        }
        gate.cancel("watch", "request")!!.cancelAndReply { out += Protocol.Kind.ERROR }
        gate.stop()!!.cancelAndReply { out += Protocol.Kind.ERROR }
        assertEquals(listOf(Protocol.Kind.RESULT), out)
        assertEquals(RequestGate.Rejection.BUSY, gate.begin("watch", "next", 1).rejection)
    }
}
