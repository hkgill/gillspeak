package dev.hkgill.gillspeak.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTest {
    private val sample = """
        {"v":1,"now":1800000000,"running":1,"waiting":1,"doneToday":3,"agents":[
          {"agent":"claude","project":"gillspeak","state":"running","task":"Running tests","activity":"Run the unit tests",
           "detail":"./gradlew test","done":4,"total":7,"started":1,"updated":2},
          {"agent":"codex","project":"gillspeak","state":"waiting","task":"","activity":"Needs approval",
           "detail":"npm test","done":0,"total":0,"started":1,"updated":3}
        ]}
    """.trimIndent()

    @Test fun parsesTheServerDocumentWaitingFirst() {
        val s = Snapshot.parse(sample)
        assertEquals(listOf("codex", "claude"), s.sessions.map { it.agent })
        assertEquals(3, s.doneToday)
        val claude = s.of(Agent.CLAUDE).single()
        assertEquals("Running tests", claude.doing)
        assertEquals(4 to 7, claude.done to claude.total)
    }

    @Test fun rejectsOtherDocuments() {
        assertThrows(IllegalArgumentException::class.java) { Snapshot.parse("<html>") }
        assertThrows(IllegalArgumentException::class.java) { Snapshot.parse("""{"v":2,"agents":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { Snapshot.parse("""{"v":1}""") }
    }

    @Test fun clampsBadNumbersAndLongText() {
        val s = Snapshot.parse("""{"v":1,"agents":[{"agent":"claude","state":"running","done":9,"total":3,"task":"${"x".repeat(500)}"},7]}""")
        val only = s.sessions.single()
        assertEquals(3, only.done)
        assertEquals(80, only.task.length)
        assertEquals(1, s.running)
    }

    @Test fun endpointFromWhatWasTyped() {
        assertEquals("http://192.168.0.20:8765/v1/agents", Endpoint.url(" 192.168.0.20:8765/ "))
        assertEquals("https://box.local/v1/agents", Endpoint.url("https://box.local/v1/agents"))
        assertNull(Endpoint.url(""))
        assertNull(Endpoint.url("http://"))
        assertNull(Endpoint.url("192.168.0.20 8765"))
        assertEquals("abcdefghjkmn", Endpoint.token(" ABCD-efgh jkmn "))
    }

    @Test fun plainHttpOnlyInsideYourOwnNetwork() {
        for (local in listOf("10.1.2.3", "172.16.0.9", "192.168.0.20", "127.0.0.1", "169.254.1.1", "100.100.1.2", "desk.local", "desk", "localhost")) {
            assertEquals(local, "http://$local:8765/v1/agents", Endpoint.url("$local:8765"))
        }
        for (remote in listOf("example.com", "172.32.0.1", "192.0.2.20", "100.128.0.1", "999.1.1.1")) {
            assertNull(remote, Endpoint.url("$remote:8765"))
            assertNull(remote, Endpoint.url("http://$remote:8765"))
        }
        assertEquals("https://example.com:8765/v1/agents", Endpoint.url("https://example.com:8765"))
        // A user name that looks like a local host doesn't make the real host local.
        assertNull(Endpoint.url("http://localhost:ignored@example.com:8765"))
        assertNull(Endpoint.url("desk.local@example.com:8765"))
        assertNull(Endpoint.url("desk:8765/other"))
    }

    @Test fun agentReadings() {
        val s = Snapshot.parse(sample)
        val claude = Face.agent(s, Agent.CLAUDE, "Off")
        assertEquals(Reading(4f, 7f, "4/7", "Claude", "Claude Code, gillspeak: Running tests, step 5 of 7"), claude)
        val codex = Face.agent(s, Agent.CODEX, "Off")
        assertEquals("Wait", codex.text)
        assertEquals("Codex, gillspeak needs you: npm test", codex.description)
        assertEquals("–", Face.agent(Snapshot(emptyList(), 0, 0, 0), Agent.CLAUDE, "Off").text)
        assertEquals("Off", Face.agent(null, Agent.CODEX, "Off").text)
    }

    @Test fun finishedAndBusyWithoutAPlan() {
        val done = Session("claude", "p", Session.DONE, "", "Finished", "", 0, 0)
        val busy = done.copy(state = Session.RUNNING, activity = "Editing Face.kt")
        assertEquals(Reading(1f, 1f, "Done", "Claude", "Claude Code, p finished"), Face.agent(Snapshot(listOf(done), 0, 0, 1), Agent.CLAUDE, "Off"))
        val r = Face.agent(Snapshot(listOf(busy, busy), 2, 0, 0), Agent.CLAUDE, "Off")
        assertEquals("Busy" to "Claude+1", r.text to r.title)
        assertTrue(r.description.endsWith("Editing Face.kt"))
    }

    @Test fun attentionTurnsOnWhenSomethingWaits() {
        val s = Snapshot.parse(sample)
        val a = Face.attention(s, "Off")
        assertEquals(1f, a.value)
        assertEquals("Needs you" to "Codex", a.text to a.title)
        assertEquals("Codex needs you: npm test", a.description)
        val calm = Face.attention(s.copy(sessions = s.sessions.filter { !it.waiting }, waiting = 0), "Off")
        assertEquals(0f to "1 agent running", calm.value to calm.text)
        assertEquals("No agents", Face.attention(Snapshot(emptyList(), 0, 0, 0), "Off").text)
        assertEquals(0f, Face.attention(null, "Set up").value)
    }

    @Test fun doneToday() {
        assertEquals(Reading(3f, 3f, "3", "done", "3 agent tasks finished today"), Face.doneToday(Snapshot(emptyList(), 0, 0, 3), "Off"))
        assertEquals("1 agent task finished today", Face.doneToday(Snapshot(emptyList(), 0, 0, 1), "Off").description)
    }
}
