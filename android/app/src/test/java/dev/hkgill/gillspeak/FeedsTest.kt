package dev.hkgill.gillspeak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class FeedsTest {
    @Test fun shortsAndReelsFromTheirIds() {
        assertEquals("YouTube Shorts", Feeds.detect("com.google.android.youtube") { it == "com.google.android.youtube:id/reel_recycler" }?.name)
        assertEquals("Instagram Reels", Feeds.detect("com.instagram.android") { it.endsWith(":id/clips_viewer_view_pager") }?.name)
    }

    @Test fun theRestOfTheAppsStaysFree() {
        assertNull(Feeds.detect("com.google.android.youtube") { false })
        assertNull(Feeds.detect("com.instagram.android") { it == "com.instagram.android:id/reel_recycler" }) // another app's id
    }

    @Test fun allOfTikTok() {
        assertEquals("TikTok", Feeds.detect("com.zhiliaoapp.musically") { false }?.name)
        assertEquals("TikTok", Feeds.detect("com.ss.android.ugc.trill") { false }?.name)
    }

    @Test fun otherAppsAreNeverFeeds() {
        assertNull(Feeds.detect("com.android.chrome") { true })
        assertNull(Feeds.detect(null) { true })
    }

    /** The service only receives events from the packages its XML lists; fail if the two drift apart. */
    @Test fun serviceWatchesExactlyTheseApps() {
        val xml = File("src/main/res/xml/focus_service.xml").readText()
        val listed = Regex("""android:packageNames="([^"]*)"""").find(xml)!!.groupValues[1].split(',').map { it.trim() }.toSet()
        assertEquals(Feeds.PACKAGES, listed)
    }
}
