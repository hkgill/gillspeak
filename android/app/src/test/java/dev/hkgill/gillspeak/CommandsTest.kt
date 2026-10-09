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
        assertEquals(Command.Alarm(7, 0), parse("Set an alarm for 7 a.m."))
        assertEquals(Command.Alarm(18, 30), parse("set an alarm for 6:30 pm"))
        assertEquals(Command.Alarm(6, 45), parse("Wake me up at six forty five in the morning."))
        assertEquals(Command.Alarm(7, 5), parse("alarm at seven oh five"))
        assertEquals(Command.Alarm(6, 30), parse("alarm for half past 6"))
        assertEquals(Command.Alarm(19, 45), parse("alarm for quarter to 8 tonight"))
        assertEquals(Command.Alarm(0, 0), parse("alarm for midnight"))
        assertEquals(Command.Alarm(12, 0), parse("alarm for noon"))
        assertEquals(Command.Alarm(7, 0), parse("alarm for 7 o'clock"))
        assertNull(parse("set an alarm for 13 pm"))
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
