package com.nova.tv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackNextEpisodeTest {
    @Test fun playbackExitRequiresTwoBackPressesWithinTimeout() {
        assertFalse(isPlaybackExitConfirmed(null, 1_000))
        assertTrue(isPlaybackExitConfirmed(1_000, 2_999))
        assertFalse(isPlaybackExitConfirmed(1_000, 3_000))
        assertFalse(isPlaybackExitConfirmed(1_000, 999))
    }

    @Test fun preloadsOnlyNearKnownEnd() {
        assertFalse(shouldPreloadNext(-1, 0))
        assertFalse(shouldPreloadNext(180_000, 119_999))
        assertTrue(shouldPreloadNext(180_000, 120_000))
        assertTrue(shouldPreloadNext(30_000, 0))
    }

    @Test fun normalEpisodesAdvanceOnlyFromKnownNonFinalItem() {
        val episodes = listOf(PlaybackEpisode("一", "https://example.test/1"), PlaybackEpisode("二", "https://example.test/2"), PlaybackEpisode("三", "https://example.test/3"))
        assertEquals(episodes[1], nextPlaybackEpisode(episodes, episodes[0].url))
        assertEquals(episodes[2], nextPlaybackEpisode(episodes, episodes[1].url))
        assertNull(nextPlaybackEpisode(episodes, episodes[2].url))
        assertNull(nextPlaybackEpisode(episodes, "unknown"))
        assertNull(nextPlaybackEpisode(emptyList(), "unknown"))
    }

    @Test fun orbitAdvanceFollowsLoadedChapterIds() {
        val episodes = listOf(OrbitEpisodeItem("a", "一", JSONObject()), OrbitEpisodeItem("b", "二", JSONObject()), OrbitEpisodeItem("c", "三", JSONObject()))
        assertEquals("b", nextOrbitEpisode(episodes, "a")?.id)
        assertEquals("c", nextOrbitEpisode(episodes, "b")?.id)
        assertNull(nextOrbitEpisode(episodes, "c"))
        assertNull(nextOrbitEpisode(episodes, "missing"))
    }
}
