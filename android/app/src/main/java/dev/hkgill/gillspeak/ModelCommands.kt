package dev.hkgill.gillspeak

import org.json.JSONObject

/**
 * Commands the rules don't recognise, understood by Gemma ([LocalLlm]). Gemma only names the action and copies
 * the words that were said ("twenty minutes", "three o'clock"); the same tested code as the rules
 * ([Commands.duration], [Commands.clockTime]) turns those words into numbers. In testing Gemma understood every
 * request but got the arithmetic wrong (twenty minutes became 120 seconds), so it never does any.
 *
 * Only [toCommand] decides what runs: it accepts the actions on the list, in the exact shape the prompt asks for,
 * and nothing else. (A JSON schema for constrained decoding cost about 3 s per command on a Galaxy S25 and still
 * let other shapes through, so the prompt spells the shapes out instead.)
 */
object ModelCommands {
    val ACTIONS = listOf("open_app", "timer", "alarm", "torch", "media", "text", "call", "navigate", "search", "none")

    val PROMPT = """
        You understand spoken commands for a phone's voice assistant. The person's words were transcribed by speech
        recognition, so expect missing punctuation and the odd misheard word. Reply with one JSON object.

        Reply with exactly one of these shapes, on one line, and nothing else:
        {"action":"open_app","app":"<the app's name>"}
        {"action":"timer","duration":"<how long, copied exactly as said, e.g. twenty minutes>"}
        {"action":"alarm","time":"<the time, copied exactly as said, e.g. three o'clock or 6:30 pm>"}
        {"action":"torch","on":true}   or   {"action":"torch","on":false}
        {"action":"media","media":"play"}   (media is one of play, pause, next, previous; skip means next)
        {"action":"text","contact":"<who>","message":"<the message, written the way they would type it>"}
        {"action":"call","contact":"<who>"}
        {"action":"navigate","place":"<where>"}
        {"action":"search","query":"<what to look up>"}
        {"action":"none"}   for anything that is not one of these commands, such as ordinary speech

        Copy durations and times exactly as said: never convert them to seconds or a 24-hour clock.
        A text message follows any instructions about it ("say sorry" means the message apologises).
        If two commands were asked for, use the first one.
    """.trimIndent()

    /** The [Command] in Gemma's answer, or null for "none" or an answer that doesn't hold up. */
    fun toCommand(json: String): Command? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        fun s(key: String) = o.optString(key).trim().takeIf { it.isNotEmpty() }
        return when (o.optString("action")) {
            "open_app" -> s("app")?.let { Command.OpenApp(it) }
            "timer" -> s("duration")?.let(Commands::duration)?.let { Command.Timer(it) }
            "alarm" -> s("time")?.let(Commands::clockTime)?.let { Command.Alarm(it.hour, it.minute, it.exact) }
            "torch" -> if (o.has("on")) Command.Torch(o.optBoolean("on")) else null
            "media" -> when (o.optString("media").lowercase()) {
                "play", "resume" -> Command.Media(MediaAction.PLAY)
                "pause", "stop" -> Command.Media(MediaAction.PAUSE)
                "next", "skip", "forward" -> Command.Media(MediaAction.NEXT)
                "previous", "back", "rewind" -> Command.Media(MediaAction.PREVIOUS)
                else -> null
            }
            "text" -> {
                val who = s("contact")
                val message = s("message")
                if (who == null || message == null) null else Command.Text("$who: $message")
            }
            "call" -> s("contact")?.let { Command.Call(it) }
            "navigate" -> s("place")?.let { Command.Navigate(it) }
            "search" -> s("query")?.let { Command.Search(it) }
            else -> null
        }
    }
}
