package io.stamethyst.ui.workshop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopYouTubeEmbedTest {
    @Test
    fun embedHtmlUsesIframeApiAndReportsPlaybackState() {
        val html = buildWorkshopYouTubeEmbedHtml("vYthsh8a1Dc")

        assertTrue(html.contains("https://www.youtube.com/iframe_api"))
        assertTrue(html.contains("https://www.youtube.com/embed/vYthsh8a1Dc?enablejsapi=1"))
        assertTrue(html.contains("""allow="autoplay; encrypted-media; fullscreen; picture-in-picture""""))
        assertTrue(html.contains("new YT.Player"))
        assertEquals("https://steamcommunity.com", WORKSHOP_YOUTUBE_EMBED_BASE_URL)
        assertTrue(html.contains("origin=https%3A%2F%2Fsteamcommunity.com"))
        assertTrue(html.contains("player.mute()"))
        assertTrue(html.contains("player.playVideo()"))
        assertTrue(html.contains("window.setTimeout(requestPlayback, 500)"))
        assertTrue(html.contains(WORKSHOP_YOUTUBE_READY_CONSOLE_MESSAGE))
        assertTrue(html.contains(WORKSHOP_YOUTUBE_PLAYING_CONSOLE_MESSAGE))
        assertTrue(html.contains(WORKSHOP_YOUTUBE_ERROR_CONSOLE_PREFIX))
    }

    @Test
    fun embedHtmlRejectsUnsafeVideoIds() {
        val html = buildWorkshopYouTubeEmbedHtml("""bad";alert(1);//""")

        assertFalse(html.contains("""bad";alert(1);//"""))
        assertTrue(html.contains(WORKSHOP_YOUTUBE_ERROR_CONSOLE_PREFIX + "invalid_video_id"))
    }
}
