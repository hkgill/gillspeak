package dev.hkgill.gillspeak.wear

import org.junit.Assert.*
import org.junit.Test

class AnswerTaskTest {
    @Test fun missingModelDoesNotRunInference() {
        val out = mutableListOf<Protocol.Kind>()
        AnswerTask.run({ false }, { false }, { error("must not run") }) { kind, _ -> out += kind }
        assertEquals(listOf(Protocol.Kind.ERROR), out)
    }
    @Test fun progressThenLocalResult() {
        val out = mutableListOf<Pair<Protocol.Kind, String>>()
        AnswerTask.run({ true }, { false }, { ready -> ready(); "local answer" }) { kind, text -> out += kind to text }
        assertEquals(listOf(Protocol.Kind.STATUS, Protocol.Kind.STATUS, Protocol.Kind.RESULT), out.map { it.first })
        assertEquals("local answer", out.last().second)
    }
    @Test fun cancelledBeforeLoadDoesNothing() {
        AnswerTask.run({ error("must not check") }, { true }, { error("must not run") }) { _, _ -> fail() }
    }
    @Test fun cancelledDuringLoadSuppressesProgressAndResult() {
        var cancelled = false; val out = mutableListOf<Protocol.Kind>()
        AnswerTask.run({ true }, { cancelled }, { ready -> cancelled = true; ready(); "late" }) { kind, _ -> out += kind }
        assertEquals(listOf(Protocol.Kind.STATUS), out)
    }
    @Test fun cancelledNativeExceptionIsNotShownAsFailure() {
        var cancelled = false; val out = mutableListOf<Protocol.Kind>()
        AnswerTask.run({ true }, { cancelled }, { cancelled = true; throw java.util.concurrent.CancellationException() }) { kind, _ -> out += kind }
        assertEquals(listOf(Protocol.Kind.STATUS), out)
    }
    @Test fun nativeFailureReturnsHelpfulErrorWithoutExceptionDetails() {
        val out = mutableListOf<String>()
        AnswerTask.run({ true }, { false }, { throw IllegalStateException("private prompt") }) { _, text -> out += text }
        assertTrue(out.last().contains("Gemma couldn't answer")); assertFalse(out.joinToString().contains("private prompt"))
    }
    @Test fun emptyResponseIsError() {
        var last: Protocol.Kind? = null
        AnswerTask.run({ true }, { false }, { "  " }) { kind, _ -> last = kind }
        assertEquals(Protocol.Kind.ERROR, last)
    }
    @Test fun longResponseBoundedForTransport() {
        var text = ""
        AnswerTask.run({ true }, { false }, { "a".repeat(10_000) }) { _, value -> text = value }
        assertEquals(Protocol.MAX_TEXT, text.length)
    }
}
