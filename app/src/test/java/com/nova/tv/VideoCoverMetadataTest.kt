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
        assertEquals("共24集 · 完结", videoEpisodeBadge(JSONObject().put("vod_total", 24).put("vod_isend", 1)))
        assertEquals("更新至3集", videoEpisodeBadge(JSONObject().put("vod_remarks", "更新至3集").put("vod_isend", 1)))
        assertEquals("完结", videoEpisodeBadge(JSONObject().put("vod_remarks", "完结")))
        assertEquals("未完结", videoEpisodeBadge(JSONObject().put("vod_remarks", "未完结")))
        assertEquals("", videoEpisodeBadge(JSONObject()))
    }
    @Test fun ratingRequiresPositiveFiniteValue() {
        assertTrue(validCoverRating("8.5"))
        listOf("", "0", "0.0", "10.1", "NaN", "Infinity", "未评分").forEach { assertFalse(validCoverRating(it)) }
    }
}
