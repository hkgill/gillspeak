package dev.hkgill.gillspeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gemma's answers become commands only through the same tested parsers as the rules: it never does the arithmetic. */
class ModelCommandsTest {
    private fun cmd(json: String) = ModelCommands.toCommand(json)

    @Test fun durationsAreWorkedOutByTheRulesNotTheModel() {
        // In the Gallery test Gemma answered "twenty minutes" with 120 seconds; now it only copies the words.
        assertEquals(Command.Timer(1200), cmd("""{"action":"timer","duration":"twenty minutes"}"""))
        assertEquals(Command.Timer(5400), cmd("""{"action":"timer","duration":"an hour and a half"}"""))
        assertNull(cmd("""{"action":"timer","duration":"a while"}"""))
        assertNull(cmd("""{"action":"timer"}"""))
    }

    @Test fun alarmTimesKeepWhetherAmOrPmWasSaid() {
        assertEquals(Command.Alarm(3, 0, exact = false), cmd("""{"action":"alarm","time":"three o'clock"}"""))
        assertEquals(Command.Alarm(14, 30), cmd("""{"action":"alarm","time":"2:30 pm"}"""))
        assertEquals(Command.Alarm(2, 30, exact = false), cmd("""{"action":"alarm","time":"2:30"}"""))
        assertNull(cmd("""{"action":"alarm","time":"before my meeting"}"""))
    }

    @Test fun everyOtherAction() {
        assertEquals(Command.OpenApp("Spotify"), cmd("""{"action":"open_app","app":"Spotify"}"""))
        assertEquals(Command.Torch(true), cmd("""{"action":"torch","on":true}"""))
        assertEquals(Command.Torch(false), cmd("""{"action":"torch","on":false}"""))
        assertEquals(Command.Media(MediaAction.NEXT), cmd("""{"action":"media","media":"next"}"""))
        assertEquals(Command.Media(MediaAction.NEXT), cmd("""{"action":"media","media":"skip"}""")) // as Gemma said it on the S25
        assertEquals(Command.Text("Sam: Sorry, I'll be 10 minutes late."), cmd("""{"action":"text","contact":"Sam","message":"Sorry, I'll be 10 minutes late."}"""))
        assertEquals(Command.Call("Mum"), cmd("""{"action":"call","contact":"Mum"}"""))
        assertEquals(Command.Navigate("Bondi"), cmd("""{"action":"navigate","place":"Bondi"}"""))
        assertEquals(Command.Search("weather tomorrow"), cmd("""{"action":"search","query":"weather tomorrow"}"""))
    }

    @Test fun noneAndBrokenAnswersAreNotCommands() {
        assertNull(cmd("""{"action":"none"}"""))
        assertNull(cmd("""{"action":"media","next"}""")) // the malformed answer from the Gallery test
        assertNull(cmd("""{"action":"format_phone"}"""))
        assertNull(cmd("""{"action":"torch"}"""))
        assertNull(cmd("""{"action":"text","contact":"Sam"}"""))
        assertNull(cmd("not json"))
    }

    @Test fun gemmaMessagesSplitOnTheColon() {
        val known = { s: String -> Commands.normalise(s) == "sam" }
        val target = (cmd("""{"action":"text","contact":"Sam","message":"Sorry, I'll be 10 minutes late."}""") as Command.Text).target
        assertEquals("Sam" to "Sorry, I'll be 10 minutes late.", Commands.splitRecipient(target, known))
    }

    @Test fun answersInOtherShapesAreRejected() {
        // Shapes Gemma produced with a schema it didn't honour: only the documented fields count.
        assertNull(cmd("""{"action":"torch","true":true}"""))
        assertNull(cmd("""{"action":"media","media_command":"skip song"}"""))
    }

    @Test fun thePromptShowsEveryAction() {
        for (action in ModelCommands.ACTIONS) assertTrue(action, ModelCommands.PROMPT.contains("\"action\":\"$action\""))
    }
}
