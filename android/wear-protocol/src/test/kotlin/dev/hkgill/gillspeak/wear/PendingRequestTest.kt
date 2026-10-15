package dev.hkgill.gillspeak.wear

import org.junit.Assert.*
import org.junit.Test

class PendingRequestTest {
    private val result = Protocol.Packet("a", Protocol.Kind.RESULT, "answer")
    @Test fun onlyExpectedPhoneAndIdAccepted() {
        val pending = PendingRequest("phone", "a", 100)
        assertFalse(pending.accept("other", result, 0))
        assertFalse(pending.accept("phone", result.copy(id = "b"), 0))
        assertTrue(pending.accept("phone", result, 0))
    }
    @Test fun lateReplyIgnored() { assertFalse(PendingRequest("phone", "a", 100).accept("phone", result, 100)) }
    @Test fun cancelledReplyIgnored() {
        val p = PendingRequest("phone", "a", 100); p.cancel(); assertFalse(p.accept("phone", result, 0))
    }
    @Test fun statusThenAnswerButNoStatusAfterAnswer() {
        val p = PendingRequest("phone", "a", 100); val status = result.copy(kind = Protocol.Kind.STATUS)
        assertTrue(p.accept("phone", status, 0)); assertFalse(p.done)
        assertTrue(p.accept("phone", result, 1)); assertTrue(p.done)
        assertFalse(p.accept("phone", status, 2)); assertFalse(p.accept("phone", result, 2))
    }
    @Test fun errorIsTerminal() {
        val p = PendingRequest("phone", "a", 100)
        assertTrue(p.accept("phone", result.copy(kind = Protocol.Kind.ERROR), 0))
        assertFalse(p.accept("phone", result, 1))
    }
    @Test fun requestsNeverAcceptedAsReplies() {
        val p = PendingRequest("phone", "a", 100)
        assertFalse(p.accept("phone", result.copy(kind = Protocol.Kind.ASK), 0))
    }
}
