package dev.hkgill.gillspeak.wear

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private val id = "99999999-1111-2222-3333-444444444444"
    private fun packet(kind: Protocol.Kind = Protocol.Kind.ASK, text: String = "Explain the Moon 🌙") = Protocol.Packet(id, kind, text, 100_000)
    @Test fun unicodeRoundTrip() { assertEquals(packet(), Protocol.decode(Protocol.encode(packet()))) }
    @Test fun everyMessageKindRoundTrips() {
        Protocol.Kind.entries.forEach { kind ->
            val p = packet(kind, if (kind in listOf(Protocol.Kind.CANCEL, Protocol.Kind.PING)) "" else "hello")
            assertEquals(p, Protocol.decode(Protocol.encode(p)))
        }
    }
    @Test fun truncatedPacketsRejected() {
        val bytes = Protocol.encode(packet())
        for (n in bytes.indices) assertNull(Protocol.decode(bytes.copyOf(n)))
    }
    @Test fun trailingBytesRejected() { assertNull(Protocol.decode(Protocol.encode(packet()) + byteArrayOf(1))) }
    @Test fun badVersionRejected() {
        val bytes = Protocol.encode(packet()); bytes[4] = 2; assertNull(Protocol.decode(bytes))
    }
    @Test fun badMagicRejected() {
        val bytes = Protocol.encode(packet()); bytes[0] = 0; assertNull(Protocol.decode(bytes))
    }
    @Test fun invalidKindRejected() {
        val bytes = Protocol.encode(packet()); bytes[43] = 100; assertNull(Protocol.decode(bytes))
    }
    @Test fun malformedUtf8Rejected() {
        val bytes = Protocol.encode(packet()); bytes[bytes.lastIndex] = 0xff.toByte(); assertNull(Protocol.decode(bytes))
    }
    @Test fun oversizedPacketsRejected() { assertNull(Protocol.decode(ByteArray(Protocol.MAX_PACKET + 1))) }
    @Test fun randomBytesDoNotThrow() {
        val random = java.util.Random(7)
        repeat(1000) { assertNull(Protocol.decode(ByteArray(random.nextInt(256)).also(random::nextBytes))) }
    }
    @Test fun questionLengthBounded() {
        assertNotNull(Protocol.decode(Protocol.encode(packet(text = "q".repeat(Protocol.MAX_QUESTION)))))
        assertThrows(IllegalArgumentException::class.java) { Protocol.encode(packet(text = "q".repeat(Protocol.MAX_QUESTION + 1))) }
    }
    @Test fun blankQuestionsRejected() { assertThrows(IllegalArgumentException::class.java) { Protocol.encode(packet(text = " \n")) } }
    @Test fun invalidIdRejected() { assertThrows(IllegalArgumentException::class.java) { Protocol.encode(packet().copy(id = "../../bad")) } }
    @Test fun cancelCannotCarryPrompt() { assertThrows(IllegalArgumentException::class.java) { Protocol.encode(packet(Protocol.Kind.CANCEL)) } }
    @Test fun freshMessagesOnly() {
        assertTrue(Protocol.fresh(packet(), 100_000))
        assertFalse(Protocol.fresh(packet(), 190_001))
        assertFalse(Protocol.fresh(packet(), 39_999))
    }
    @Test fun largestUnicodeReplyFitsMessageLimit() {
        val p = packet(Protocol.Kind.RESULT, "界".repeat(Protocol.MAX_TEXT))
        assertTrue(Protocol.encode(p).size < Protocol.MAX_PACKET)
        assertEquals(p, Protocol.decode(Protocol.encode(p)))
    }
}
