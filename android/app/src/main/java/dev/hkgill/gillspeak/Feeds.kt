package dev.hkgill.gillspeak

/**
 * The short-video feeds Mindful mode watches, spotted from the apps' own view ids, never from what's on screen. A feed
 * with no ids is the whole app. Normal videos, the home feed, search and messages have none of these ids, so they
 * stay free. The ids change when the apps do: check them with the probe in [FocusService] after app updates.
 */
object Feeds {
    data class Feed(val name: String, val packages: Set<String>, val ids: List<String>)

    val ALL = listOf(
        Feed("YouTube Shorts", setOf("com.google.android.youtube"), listOf("reel_recycler", "reel_player_page_container")),
        Feed("Instagram Reels", setOf("com.instagram.android"), listOf("clips_viewer_view_pager", "clips_viewer_pager")),
        Feed("TikTok", setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), emptyList()),
    )

    /** Every package watched; focus_service.xml must list the same ones. */
    val PACKAGES = ALL.flatMap { it.packages }.toSet()

    /** The feed on screen, if any: [pkg] is the app in front, [visible] says if a view with a full id is showing. */
    fun detect(pkg: String?, visible: (String) -> Boolean): Feed? {
        val feed = ALL.firstOrNull { pkg in it.packages } ?: return null
        return feed.takeIf { it.ids.isEmpty() || it.ids.any { id -> visible("$pkg:id/$id") } }
    }
}
