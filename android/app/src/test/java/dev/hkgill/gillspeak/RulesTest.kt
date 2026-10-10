package dev.hkgill.gillspeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases copied from gillspeak's tests/test_rules.py and tests/test_validate.py, so the port stays faithful. */
class RulesTest {
    private fun clean(raw: String, dictionary: Dictionary = Dictionary(), aggressive: Boolean = false, commands: Boolean = true) =
        Rules(dictionary, aggressiveFillers = aggressive, spokenCommands = commands).apply(raw)

    @Test fun fillers() {
        mapOf(
            "Um, I think we should go." to "I think we should go.",
            "I think, uh, we should go." to "I think, we should go.",
            "So we're done, uh." to "So we're done.",
            "Ummm hello there" to "Hello there",
            "hmm let me see" to "Let me see",
            "The umbrella is here" to "The umbrella is here",
            "Her name is Erma" to "Her name is Erma",
            "I like it, you know." to "I like it, you know.",
        ).forEach { (raw, expected) -> assertEquals(raw, expected, clean(raw)) }
    }

    @Test fun aggressiveFillers() {
        assertEquals("It was really good.", clean("It was like really good, you know.", aggressive = true))
    }

    @Test fun stutters() {
        mapOf(
            "I I think so" to "I think so",
            "the the the plan" to "the plan",
            "The the plan" to "The plan",
            "I know that that is true" to "I know that that is true",
            "She had had enough" to "She had had enough",
            "version 2 2 ships" to "version 2 2 ships",
        ).forEach { (raw, expected) -> assertEquals(raw, expected, collapseStutters(raw)) }
    }

    @Test fun spokenCommands() {
        mapOf(
            "Hello there. New paragraph. How are you?" to "Hello there.\n\nHow are you?",
            "first line new line second line" to "First line\nSecond line",
            "Shopping list bullet point milk bullet point eggs" to "Shopping list\n- Milk\n- Eggs",
            "Are you coming, question mark." to "Are you coming?",
            "See you then, period." to "See you then.",
            "See you then full stop" to "See you then.",
            "The period of time was long" to "The period of time was long",
            "A period, then more words" to "A period, then more words",
            "Hello new paragraph" to "Hello\n\n",
        ).forEach { (raw, expected) -> assertEquals(raw, expected, clean(raw)) }
    }

    @Test fun disabledAndOffCommands() {
        assertEquals("wait new line here", applySpokenCommands("wait new line here", setOf("new line")))
        assertEquals("Hello new line there", clean("hello new line there", commands = false))
    }

    @Test fun dictionary() {
        val d = Dictionary(mapOf("super base" to "Supabase", "super base studio" to "Supabase Studio", "get hub" to "GitHub"))
        assertEquals("Open Supabase Studio on GitHub.", d.apply("Open Super Base Studio on get hub."))
        assertEquals("use Supabase now", d.apply("use super  base now"))
        assertEquals("superbase", d.apply("superbase"))
        val k = Dictionary(mapOf("cube control" to "kubectl"))
        assertEquals("cube controls", k.apply("cube controls"))
        assertEquals("run kubectl, then", k.apply("run cube control, then"))
    }

    @Test fun dictionaryParse() {
        val d = Dictionary.parse("[replace]\n# comment\n\"super base\" = \"Supabase\"\nget hub = GitHub\nbroken line", "Supabase, \"Fedora\"\nParakeet")
        assertEquals(mapOf("super base" to "Supabase", "get hub" to "GitHub"), d.replace)
        assertEquals(listOf("Supabase", "Fedora", "Parakeet"), d.bias)
    }

    @Test fun whitespace() {
        mapOf(
            "hello   world" to "Hello world",
            "hello , world ." to "Hello, world.",
            "  hi there " to "Hi there",
            "wait..." to "Wait...",
            "done.." to "Done.",
            "" to "",
            "   " to "",
        ).forEach { (raw, expected) -> assertEquals(raw, expected, normaliseWhitespace(raw)) }
    }

    @Test fun fullPipeline() {
        val d = Dictionary(mapOf("super base" to "Supabase"))
        val raw = "Um, so the the super base migration is, uh, done. New paragraph. Can you check it, question mark"
        assertEquals("So the Supabase migration is, done.\n\nCan you check it?", clean(raw, d))
    }

    @Test fun fillersInsideWordsSurvive() {
        assertTrue("humming" in removeFillers("humming along"))
        assertTrue("ahead" in removeFillers("go ahead"))
    }

    @Test fun validator() {
        assertEquals("", Validate.check("meet at 9 30 on Friday", "Meet at 9:30 on Friday."))
        assertEquals("missing_number", Validate.check("send 250 dollars to Sam today please", "Send 1250 dollars to Sam today, please."))
        assertEquals("", Validate.check("Thursday sorry Friday at 3", "Friday at 3."))
        assertEquals("missing_number", Validate.check("Pay 1.50 now.", "Pay 150 now."))
        assertEquals("missing_number", Validate.check("Pay 150 now.", "Pay 1.50 now."))
        assertEquals("", Validate.check("Item 1.50 4 8 2 now.", "Item 1.50 482 now."))
        assertEquals("missing_number", Validate.check("Item 1.50 4 8 2 now.", "Item 150 482 now."))
        assertEquals("", Validate.check("Total 1,299.95 today.", "Total 1,299.95 today."))
        // Gemma 4 E2B on a Galaxy S25 swapped in Hindi mid-sentence.
        assertEquals("new_script", Validate.check(
            "can you please leave the back door open because i forgot my keys",
            "Can you please leave the back door open क्योंकि I forgot my keys?"))
        assertEquals("", Validate.check("ਮੈਂ ਘਰ ਆ ਰਿਹਾ ਹਾਂ okay", "ਮੈਂ ਘਰ ਆ ਰਿਹਾ ਹਾਂ, okay."))
        assertEquals("", Validate.check("the cafe is open", "The café is open."))
        assertEquals("preamble", Validate.check("the report is done and sent", "Sure, the report is done and sent."))
        assertEquals("empty", Validate.check("hello", "  "))
        assertTrue(Validate.check("hi there", "Hi there, this is a much longer answer than asked").startsWith("too_long"))
    }
}
