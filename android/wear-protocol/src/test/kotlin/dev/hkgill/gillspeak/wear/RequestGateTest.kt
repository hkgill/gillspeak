package dev.hkgill.gillspeak.wear

import org.junit.Assert.*
import org.junit.Test

class RequestGateTest {
    @Test fun onlyOneNativeJobAdmitted() {
        val gate = RequestGate()
        assertNotNull(gate.begin("watch", "a", 0).ticket)
        assertEquals(RequestGate.Rejection.BUSY, gate.begin("watch", "b", 0).rejection)
    }
    @Test fun duplicateDoesNotRunAgainAfterCompletion() {
        val gate = RequestGate(); val ticket = gate.begin("watch", "a", 0).ticket!!
        gate.finish(ticket)
        assertEquals(RequestGate.Rejection.DUPLICATE, gate.begin("watch", "a", 1).rejection)
    }
    @Test fun cancelCannotTargetAnotherWatch() {
        val gate = RequestGate(); val ticket = gate.begin("watch", "a", 0).ticket!!
        assertNull(gate.cancel("other", "a")); assertFalse(ticket.cancelled.get())
    }
    @Test fun cancelCannotTargetAnotherRequest() {
        val gate = RequestGate(); val ticket = gate.begin("watch", "a", 0).ticket!!
        assertNull(gate.cancel("watch", "b")); assertFalse(ticket.cancelled.get())
    }
    @Test fun cancellationKeepsBusyUntilNativeWorkerExits() {
        val gate = RequestGate(); val ticket = gate.begin("watch", "a", 0).ticket!!
        gate.cancel("watch", "a")
        assertTrue(ticket.stopped(1))
        assertEquals(RequestGate.Rejection.BUSY, gate.begin("watch", "b", 1).rejection)
        gate.finish(ticket)
        assertNotNull(gate.begin("watch", "b", 2).ticket)
    }
    @Test fun cancellationBeforeRequestMakesTombstone() {
        val gate = RequestGate(); gate.cancel("watch", "a")
        assertEquals(RequestGate.Rejection.DUPLICATE, gate.begin("watch", "a", 0).rejection)
    }
    @Test fun deadlineStopsWaitingForModel() {
        val ticket = RequestGate().begin("watch", "a", 100).ticket!!
        assertFalse(ticket.stopped(100 + Protocol.TIMEOUT_MS - 1))
        assertTrue(ticket.stopped(100 + Protocol.TIMEOUT_MS))
    }
    @Test fun shutdownCancelsCurrentRequest() {
        val gate = RequestGate(); val ticket = gate.begin("watch", "a", 0).ticket!!
        assertSame(ticket, gate.stop()); assertTrue(ticket.stopped(0))
    }
    @Test fun staleWorkerCannotReleaseAnotherSlot() {
        val gate = RequestGate(); val old = gate.begin("watch", "a", 0).ticket!!
        gate.finish(old); gate.begin("watch", "b", 1)
        gate.finish(old)
        assertEquals(RequestGate.Rejection.BUSY, gate.begin("watch", "c", 2).rejection)
    }
    @Test fun simultaneousRequestsOnlyAdmitOne() {
        val gate = RequestGate(); val admitted = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..32).map { index -> Thread { if (gate.begin("watch", "$index", 0).ticket != null) admitted.incrementAndGet() } }
        threads.forEach(Thread::start); threads.forEach(Thread::join)
        assertEquals(1, admitted.get())
    }
}
