package dev.hkgill.gillspeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandsTest {
    private fun parse(s: String) = Commands.parse(s)

    @Test fun opensApps() {
        assertEquals(Command.OpenApp("camera"), parse("Open the camera."))
        assertEquals(Command.OpenApp("Spotify"), parse("Launch Spotify"))
        assertEquals(Command.OpenApp("Strava"), parse("Please open the Strava app."))
        assertEquals(Command.OpenApp("Google Maps"), parse("open up Google Maps"))
    }

    @Test fun timers() {
        assertEquals(Command.Timer(600), parse("Set a timer for 10 minutes."))
        assertEquals(Command.Timer(300), parse("Set up a timer for five minutes."))
        assertEquals(Command.Timer(300), parse("create a new timer of 5 minutes"))
        assertEquals(Command.Timer(600), parse("set a timer for ten minutes"))
        assertEquals(Command.Timer(600), parse("10 minute timer"))
        assertEquals(Command.Timer(600), parse("Start a 10-minute timer."))
        assertEquals(Command.Timer(1500), parse("timer for twenty five minutes"))
        assertEquals(Command.Timer(5400), parse("Set a timer for an hour and a half"))
        assertEquals(Command.Timer(5400), parse("timer one and a half hours"))
        assertEquals(Command.Timer(1800), parse("set a timer for half an hour"))
        assertEquals(Command.Timer(4800), parse("timer for 1 hour 20 minutes"))
        assertEquals(Command.Timer(90), parse("90 second timer"))
        assertEquals(Command.Timer(300), parse("timer 5min"))
        assertNull(parse("set a timer"))
    }

    @Test fun alarms() {
        assertEquals(Command.Alarm(3, 0, exact = false), parse("Set up an alarm for three o'clock."))
        assertEquals(Command.Alarm(3, 0, exact = false), parse("Set up an alarm for three o\u2019clock."))
        assertEquals(Command.Alarm(15, 0), parse("create an alarm at 3 pm"))
        assertEquals(Command.Alarm(17, 30), parse("alarm for 17:30"))
        assertEquals(Command.Alarm(6, 0, exact = false), parse("wake me up at six"))
        assertEquals(Command.Alarm(7, 0), parse("Set an alarm for 7 a.m."))
        assertEquals(Command.Alarm(18, 30), parse("set an alarm for 6:30 pm"))
        assertEquals(Command.Alarm(6, 45), parse("Wake me up at six forty five in the morning."))
        assertEquals(Command.Alarm(7, 5, exact = false), parse("alarm at seven oh five"))
        assertEquals(Command.Alarm(6, 30, exact = false), parse("alarm for half past 6"))
        assertEquals(Command.Alarm(19, 45), parse("alarm for quarter to 8 tonight"))
        // Regression (Codex review): am/pm belongs to the hour named, before stepping back an hour.
        assertEquals(Command.Alarm(11, 45), parse("alarm for quarter to 12 pm"))
        assertEquals(Command.Alarm(23, 45), parse("alarm for quarter to 12 am"))
        assertEquals(Command.Alarm(0, 45), parse("alarm for quarter to 1 am"))
        assertEquals(Command.Alarm(12, 45), parse("alarm for quarter to 1 pm"))
        assertEquals(Command.Alarm(0, 45, exact = false), parse("alarm for quarter to 1")) // 12:45, am or pm: the next one
        assertEquals(Command.Alarm(0, 0), parse("alarm for midnight"))
        assertEquals(Command.Alarm(12, 0), parse("alarm for noon"))
        assertEquals(Command.Alarm(7, 0, exact = false), parse("alarm for 7 o'clock"))
        assertNull(parse("set an alarm for 13 pm"))
    }

    @Test fun alarmsWithoutAmOrPmGoToTheNextOne() {
        val at = { h: Int, m: Int -> h * 60 + m }
        assertEquals(15 to 0, Commands.nextOccurrence(3, 0, at(10, 8))) // "three o'clock" mid-morning: 3 pm
        assertEquals(3 to 0, Commands.nextOccurrence(3, 0, at(22, 0))) // late evening: 3 am
        assertEquals(7 to 0, Commands.nextOccurrence(7, 0, at(23, 30)))
        assertEquals(19 to 0, Commands.nextOccurrence(7, 0, at(7, 0))) // exactly now: the next one
        assertEquals(12 to 0, Commands.nextOccurrence(12, 0, at(9, 0)))
        assertEquals(0 to 0, Commands.nextOccurrence(12, 0, at(13, 0)))
    }

    @Test fun torchAndMedia() {
        assertEquals(Command.Torch(true), parse("Turn on the torch."))
        assertEquals(Command.Torch(false), parse("turn the flashlight off"))
        assertEquals(Command.Torch(true), parse("Flashlight on"))
        assertEquals(Command.Media(MediaAction.PAUSE), parse("Pause."))
        assertEquals(Command.Media(MediaAction.PAUSE), parse("stop the music"))
        assertEquals(Command.Media(MediaAction.PLAY), parse("Resume"))
        assertEquals(Command.Media(MediaAction.NEXT), parse("Next song."))
        assertEquals(Command.Media(MediaAction.NEXT), parse("skip this track"))
        assertEquals(Command.Media(MediaAction.PREVIOUS), parse("previous track"))
    }

    @Test fun peopleAndPlaces() {
        assertEquals(Command.Text("Sam I'm running late"), parse("Text Sam I'm running late."))
        assertEquals(Command.Text("Mum, saying I'll be there at 6"), parse("Send a message to Mum, saying I'll be there at 6"))
        assertEquals(Command.Call("Mum"), parse("Call Mum."))
        assertEquals(Command.Navigate("the airport"), parse("Navigate to the airport"))
        assertEquals(Command.Navigate("Bondi Beach"), parse("Take me to Bondi Beach."))
        assertEquals(Command.Search("parakeet speech model"), parse("Search for parakeet speech model"))
    }

    @Test fun calendarEvents() {
        assertEquals(Command.Event("", null, null), parse("Add an event to the calendar."))
        assertEquals(Command.Event("Dentist", "tomorrow", "3"), parse("Add dentist tomorrow at 3 to my calendar"))
        assertEquals(Command.Event("Meeting with Sam", "friday", "2:30"), parse("Schedule a meeting with Sam on Friday at 2:30"))
        assertEquals(Command.Event("Appointment", "tomorrow", "9 am"), parse("Book an appointment for tomorrow at 9 am"))
        assertEquals(Command.Event("Lunch with Mum", null, "noon"), parse("Create an event called lunch with Mum at noon"))
        assertEquals(Command.Event("Team standup", "monday", null), parse("put team standup in my calendar for next Monday"))
    }

    @Test fun calendarTimes() {
        val now = java.time.LocalDateTime.of(2026, 10, 10, 11, 38) // a Saturday
        fun at(day: String?, time: String?) = Commands.eventStart(day, time, now)
        assertNull(at(null, null))
        assertEquals(Commands.EventTime(java.time.LocalDateTime.of(2026, 10, 11, 15, 0), false), at("tomorrow", "3"))
        assertEquals(Commands.EventTime(java.time.LocalDateTime.of(2026, 10, 16, 14, 30), false), at("friday", "2:30"))
        assertEquals(Commands.EventTime(java.time.LocalDateTime.of(2026, 10, 11, 9, 0), false), at("tomorrow", "9 am"))
        assertEquals(Commands.EventTime(java.time.LocalDateTime.of(2026, 10, 10, 12, 0), false), at(null, "noon"))
        assertEquals(Commands.EventTime(java.time.LocalDateTime.of(2026, 10, 11, 10, 0), false), at(null, "10")) // 10 am has passed today
        assertEquals(Commands.EventTime(java.time.LocalDateTime.of(2026, 10, 10, 19, 0), false), at("tonight", "7"))
        // Regression (CodeRabbit): "quarter to 1" with no am/pm booked 00:45.
        assertEquals(Commands.EventTime(java.time.LocalDateTime.of(2026, 10, 11, 12, 45), false), at("tomorrow", "quarter to 1"))
        assertEquals(Commands.EventTime(java.time.LocalDate.of(2026, 10, 12).atStartOfDay(), true), at("monday", null))
        assertEquals(Commands.EventTime(java.time.LocalDate.of(2026, 10, 17).atStartOfDay(), true), at("saturday", null)) // next week's
        assertEquals(Commands.EventTime(java.time.LocalDateTime.of(2026, 10, 16, 14, 30), false), at("Friday", "2:30")) // as Gemma writes it
    }

    @Test fun politePrefixesAreIgnored() {
        assertEquals(Command.OpenApp("camera"), parse("Hey gillspeak, open the camera"))
        assertEquals(Command.Timer(120), parse("Could you please set a timer for 2 minutes?"))
    }

    @Test fun dictationIsNotACommand() {
        assertNull(parse("I think the meeting went well today."))
        assertNull(parse(""))
    }

    @Test fun recipientsSplitOnKnownContacts() {
        val contacts = setOf("sam", "mum", "sam jones")
        val known = { s: String -> Commands.normalise(s) in contacts }
        assertEquals("Sam" to "I'm running late.", Commands.splitRecipient("Sam I'm running late", known))
        assertEquals("Sam Jones" to "On my way!", Commands.splitRecipient("Sam Jones on my way!", known))
        assertEquals("Mum" to "I'll be there at 6.", Commands.splitRecipient("Mum, saying I'll be there at 6", known))
        assertEquals("Mum" to "Dinner's ready.", Commands.splitRecipient("to Mum that dinner's ready", known))
        assertNull(Commands.splitRecipient("Bob hello", known))
    }

    @Test fun appNamesMatchLoosely() {
        val apps = listOf("Camera", "Camera Assistant", "Spotify", "YouTube", "Google Maps", "Messages", "Settings")
        assertEquals(0, Commands.bestMatch("camera", apps))
        assertEquals(2, Commands.bestMatch("spotify", apps))
        assertEquals(2, Commands.bestMatch("spottify", apps))
        assertEquals(3, Commands.bestMatch("you tube", apps))
        assertEquals(4, Commands.bestMatch("maps", apps))
        assertEquals(5, Commands.bestMatch("messages", apps))
        assertNull(Commands.bestMatch("strava", apps))
    }
}
