package dev.hkgill.gillspeak

/**
 * The short-video feeds Mindful mode watches, spotted from the apps' own layout, never from what's on screen. A feed
 * with no markers is the whole app. Normal videos, the home feed, search and messages have none of these markers, so
 * they stay free. The markers change when the apps do: check them with the probe in [FocusService] after app updates.
 */
object Feeds {
    /**
     * [ids]: view ids of the feed's player. [labels]: the exact accessibility label of a view covering the feed, for
     * apps that hide their view ids (Facebook). [tabs]: the start of a tab's label, counted only while it's selected.
     * Labels are in English, so they only match when the app is in English.
     */
    data class Feed(
        val name: String,
        val packages: Set<String>,
        val ids: List<String> = emptyList(),
        val labels: List<String> = emptyList(),
        val tabs: List<String> = emptyList(),
    ) {
        val wholeApp get() = ids.isEmpty() && labels.isEmpty() && tabs.isEmpty()
    }

    /** What [detect] may ask about the screen in front. */
    interface Screen {
        /** A visible view with this full id ("package:id/name"). */
        fun hasId(id: String): Boolean
        /** A visible view labelled exactly [label]. */
        fun hasLabel(label: String): Boolean
        /** A visible, selected view whose label starts with [prefix]. */
        fun hasSelectedTab(prefix: String): Boolean
    }

    val ALL = listOf(
        Feed("YouTube Shorts", setOf("com.google.android.youtube"), ids = listOf("reel_recycler", "reel_player_page_container")),
        Feed("Instagram Reels", setOf("com.instagram.android"), ids = listOf("clips_viewer_view_pager", "clips_viewer_pager")),
        Feed("TikTok", setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")),
        // Facebook's view ids are all hidden. The Reels tab has a full-screen "Reels tab details" view and its tab is
        // selected; a reel opened from the feed fills the screen as "Reel details". The feed's reel tiles ("Reel",
        // "View reel by …") don't count, so the rest of Facebook stays free.
        Feed("Facebook Reels", setOf("com.facebook.katana"), labels = listOf("Reels tab details", "Reel details"), tabs = listOf("Reels, tab ")),
    )

    /** Every package watched; focus_service.xml must list the same ones. */
    val PACKAGES = ALL.flatMap { it.packages }.toSet()

    /** The feed on screen, if any: [pkg] is the app in front. */
    fun detect(pkg: String?, screen: Screen): Feed? {
        val feed = ALL.firstOrNull { pkg in it.packages } ?: return null
        return feed.takeIf {
            it.wholeApp || it.ids.any { id -> screen.hasId("$pkg:id/$id") } || it.labels.any(screen::hasLabel) ||
                it.tabs.any(screen::hasSelectedTab)
        }
    }
}
