package dev.hkgill.gillspeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class FeedsTest {
    /** A screen with these visible ids, labels and selected tab labels. */
    private fun screen(ids: Set<String> = emptySet(), labels: Set<String> = emptySet(), selected: Set<String> = emptySet()) =
        object : Feeds.Screen {
            override fun hasId(id: String) = id in ids
            override fun hasLabel(label: String) = label in labels
            override fun hasSelectedTab(prefix: String) = selected.any { it.startsWith(prefix) }
        }

    private val nothing = screen()

    @Test fun shortsAndReelsFromTheirIds() {
        assertEquals("YouTube Shorts", Feeds.detect("com.google.android.youtube", screen(ids = setOf("com.google.android.youtube:id/reel_recycler")))?.name)
        assertEquals("Instagram Reels", Feeds.detect("com.instagram.android", screen(ids = setOf("com.instagram.android:id/clips_viewer_view_pager")))?.name)
    }

    @Test fun theRestOfTheAppsStaysFree() {
        assertNull(Feeds.detect("com.google.android.youtube", nothing))
        assertNull(Feeds.detect("com.instagram.android", screen(ids = setOf("com.instagram.android:id/reel_recycler")))) // another app's id
    }

    @Test fun allOfTikTok() {
        assertEquals("TikTok", Feeds.detect("com.zhiliaoapp.musically", nothing)?.name)
        assertEquals("TikTok", Feeds.detect("com.ss.android.ugc.trill", nothing)?.name)
    }

    // The Facebook cases are what a phone showed (Facebook 2026-10): its view ids are all hidden.
    @Test fun facebookReelsTab() {
        val tab = screen(labels = setOf("Reels tab details", "Reels"), selected = setOf("Reels, tab 2 of 6"))
        assertEquals("Facebook Reels", Feeds.detect("com.facebook.katana", tab)?.name)
        // The tab alone, before the full-screen view is labelled.
        assertEquals("Facebook Reels", Feeds.detect("com.facebook.katana", screen(selected = setOf("Reels, tab 2 of 6")))?.name)
    }

    @Test fun facebookReelOpenedFromTheFeed() {
        assertEquals("Facebook Reels", Feeds.detect("com.facebook.katana", screen(labels = setOf("Reel details")))?.name)
    }

    @Test fun theFacebookFeedStaysFree() {
        // Reel tiles in the feed, and the Reels tab showing but not selected (also with a "1 new" badge).
        val feed = screen(labels = setOf("Reel", "Reels", "View reel by Someone", "Hide reel 1", "Reels, tab 2 of 6, 1 new"))
        assertNull(Feeds.detect("com.facebook.katana", feed))
        assertNull(Feeds.detect("com.facebook.katana", screen(selected = setOf("Home, tab 1 of 6"))))
    }

    @Test fun otherAppsAreNeverFeeds() {
        val everything = screen(labels = setOf("Reel details", "Reels tab details"), selected = setOf("Reels, tab 2 of 6"))
        assertNull(Feeds.detect("com.android.chrome", everything))
        assertNull(Feeds.detect(null, everything))
    }

    /** The service only receives events from the packages its XML lists; fail if the two drift apart. */
    @Test fun serviceWatchesExactlyTheseApps() {
        val xml = File("src/main/res/xml/focus_service.xml").readText()
        val listed = Regex("""android:packageNames="([^"]*)"""").find(xml)!!.groupValues[1].split(',').map { it.trim() }.toSet()
        assertEquals(Feeds.PACKAGES, listed)
    }
}
