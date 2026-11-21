package com.nova.tv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VideoCoverMetadataTest {
    @Test fun episodeBadgesUseAvailableSourceFields() {
        assertEquals("更新至第12集", videoEpisodeBadge(JSONObject().put("vod_remarks", "更新至第12集").put("vod_total", 24)))
        assertEquals("更新至5集", videoEpisodeBadge(JSONObject().put("vod_serial", "5")))
        assertEquals("共24集", videoEpisodeBadge(JSONObject().put("vod_total", 24)))
        assertEquals("", videoEpisodeBadge(JSONObject().put("vod_remarks", "HD").put("vod_serial", "0")))
        assertEquals("", videoEpisodeBadge(JSONObject()))
    }
    @Test fun ratingRequiresPositiveFiniteValue() {
        assertTrue(validCoverRating("8.5"))
        listOf("", "0", "NaN", "Infinity", "未评分").forEach { assertFalse(validCoverRating(it)) }
    }
}
