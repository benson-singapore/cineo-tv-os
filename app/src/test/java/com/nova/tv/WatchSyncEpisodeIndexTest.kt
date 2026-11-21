package com.nova.tv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WatchSyncEpisodeIndexTest {
    @Test fun sourcePositionDoesNotDependOnTitleNumbers() {
        val episodes = parseLineEpisodes("序章\$https://example.test/a#2024 特别篇 第9集\$https://example.test/b") { it }
        assertEquals(listOf(0, 1), episodes.map { it.lineEpisodeIndex })
        assertEquals("source:video:A:0", watchProgressSyncKey("source:video", "A", episodes[0]))
        assertEquals("source:video:A:1", watchProgressSyncKey("source:video", "A", episodes[1]))
        assertEquals("source:video:B:1", watchProgressSyncKey("source:video", "B", episodes[1]))
        assertEquals("source:video:%E7%BA%BF%E8%B7%AF%3AA:1", watchProgressSyncKey("source:video", "线路:A", episodes[1]))
    }

    @Test fun sourceHolesKeepTheirOriginalIndexAndLegacyWithoutIndexIsNotSynced() {
        val episodes = parseLineEpisodes("第一\$u1##第二\$u2") { it }
        assertEquals(listOf(0, 2), episodes.map { it.lineEpisodeIndex })
        val old = episodeFromHistory(JSONObject().put("episodeTitle", "第2集").put("episodeUrl", "u2"))
        assertNull(old.lineEpisodeIndex)
        assertNull(watchProgressSyncKey("source:video", "A", old))
        val restored = episodeFromHistory(JSONObject().put("episodeTitle", "无数字").put("episodeUrl", "u2").put("lineEpisodeIndex", 1))
        assertEquals(1, restored.lineEpisodeIndex)
        assertEquals("source:video:A:1", watchProgressSyncKey("source:video", "A", restored))
        assertNull(episodeFromHistory(JSONObject().put("lineEpisodeIndex", -1)).lineEpisodeIndex)
        assertNull(watchProgressSyncKey("source:video", "", restored))
    }

    @Test fun duplicateLineNamesCannotBeSafelySynced() {
        val ep = PlaybackEpisode("名字", "u", 0)
        val lines = disableAmbiguousLineIndices(listOf(
            PlaybackLine("A", listOf(ep)), PlaybackLine("A", listOf(ep.copy(url = "v"))),
            PlaybackLine("B", listOf(ep))
        ))
        assertNull(watchProgressSyncKey("source:video", "A", lines[0].episodes.single()))
        assertNull(watchProgressSyncKey("source:video", "A", lines[1].episodes.single()))
        assertEquals("source:video:B:0", watchProgressSyncKey("source:video", "B", lines[2].episodes.single()))
    }
    @Test fun unmarkedOrMismatchedQueueEntriesAreRetainedNotSent() {
        val api = "https://example.test/api"
        val videoKey = "${hubSourceKey(api)}:42"
        val payload = mapOf<String, Any?>(
            "source_api" to api, "video" to mapOf("remoteId" to "42"),
            "line_name" to "A", "episode_index" to 1, "cineo_index_base" to 0
        )
        val progress = SyncEnvelope("progress", "$videoKey:A:1", 1, false, payload)
        val history = SyncEnvelope("history", videoKey, 1, false, payload)
        assertTrue(hasTrustedWatchIdentity(progress))
        assertTrue(hasTrustedWatchIdentity(history))
        assertTrue(hasTrustedWatchIdentity(SyncEnvelope("site", "anything", 1, false, null)))
        assertFalse(hasTrustedWatchIdentity(progress.copy(payload = payload - "cineo_index_base")))
        assertFalse(hasTrustedWatchIdentity(progress.copy(key = "$videoKey:A:0")))
        assertFalse(hasTrustedWatchIdentity(progress.copy(payload = payload + ("source_api" to "https://other.test/api"))))
        assertFalse(hasTrustedWatchIdentity(progress.copy(deleted = true, payload = null)))
        assertFalse(hasTrustedWatchIdentity(history.copy(payload = payload + ("episode_index" to -1))))
    }

    @Test fun uncertainWatchRecordsDoNotBlockIndependentItems() {
        val api = "https://example.test/api"
        val source = hubSourceKey(api)
        fun record(vod: String): SyncEnvelope {
            val payload = mapOf<String, Any?>("source_api" to api, "video" to mapOf("remoteId" to vod),
                "line_name" to "A", "episode_index" to 0, "cineo_index_base" to 0)
            return SyncEnvelope("progress", "$source:$vod:A:0", 1, false, payload)
        }
        val old = SyncEnvelope("history", "$source:42:A:0", 1, true, null)
        val same = record("42")
        val other = record("43")
        val site = SyncEnvelope("site", source, 1, false, null)
        val (safe, held) = partitionWatchOutbox(listOf(old, same, other, site))
        assertEquals(listOf(other, site), safe)
        assertEquals(listOf(old, same), held)
        val unknown = old.copy(key = "broken")
        assertEquals(listOf(site), partitionWatchOutbox(listOf(unknown, other, site)).first)
    }

    @Test fun incompleteAcknowledgementPreservesBatch() {
        val batch = listOf(SyncEnvelope("site", "a", 1, false, null), SyncEnvelope("site", "b", 1, false, null))
        val partial = JSONObject("""{"accepted":[{"entity":"site","key":"a"}],"ignored":[]}""")
        assertNull(confirmedSyncCounts(partial, batch))
        val full = JSONObject("""{"accepted":[{"entity":"site","key":"a"}],"ignored":[{"entity":"site","key":"b"}]}""")
        assertEquals(1 to 1, confirmedSyncCounts(full, batch))
    }

    @Test fun skippedLegacyHistoryIsVisibleAndNeverReportedComplete() {
        val result = fullPushCompletion(sent = 0, ignored = 0, failed = 0, skipped = 2)
        assertEquals("推送部分完成", result.label)
        assertTrue(result.detail!!.contains("跳过 2 条观看记录"))
        assertTrue(result.detail!!.contains("本地记录已保留"))
        assertEquals("推送完成", fullPushCompletion(2, 1, 0, 0).label)
    }

    @Test fun syncedHistoryResolvesOnlyExactSourceLineAndEpisode() {
        val video = RemoteVideo("site:42", "site", "站点", "42", "影片", "剧集", "2026", "", "", "")
        val first = PlaybackEpisode("首集", "https://example.test/1", 0)
        val second = PlaybackEpisode("特别篇2024", "https://example.test/2", 1)
        val detail = RemoteVideoDetail(video, listOf(PlaybackLine("A", listOf(first, second)), PlaybackLine("B", listOf(first))))
        val record = WatchRecord(video, "A", PlaybackEpisode("特别篇2024", "", 1), 60_000, 120_000, 100)
        assertEquals(second, resolveSyncedEpisode(record, detail)?.second)
        assertNull(resolveSyncedEpisode(record.copy(video = video.copy(sourceId = "other")), detail))
        assertNull(resolveSyncedEpisode(record.copy(episode = record.episode.copy(lineEpisodeIndex = null)), detail))
        assertNull(resolveSyncedEpisode(record, detail.copy(lines = detail.lines + PlaybackLine("A", listOf(first)))))
        assertNull(resolveSyncedEpisode(record.copy(episode = record.episode.copy(lineEpisodeIndex = 9)), detail))
    }

    @Test fun flutterVideoPayloadAndLegacyEnvelopeResolveFromExactKey() {
        val source = VideoSource("site", "站点", "https://example.test/api")
        val sourceKey = hubSourceKey(source.api)
        val key = "$sourceKey:42:A:1"
        val payload = JSONObject().put("title", "影片").put("source_api", source.api)
            .put("source_key", sourceKey).put("video", JSONObject().put("remoteId", "42").put("posterUrl", "https://example.test/cover"))
        val video = decodeHubVideo(key, payload, listOf(source))!!.first
        assertEquals("site:42", video.id)
        assertEquals("影片", video.title)
        assertEquals("42", video.remoteId)
        val old = JSONObject().put("title", "影片").put("video", JSONObject().put("posterUrl", "https://example.test/cover"))
        assertEquals("42", decodeHubVideo(key, old, listOf(source))!!.first.remoteId)
        assertNull(decodeHubVideo(key, payload, listOf(source.copy(id = "other", api = "https://other.test/api"))))
        assertNull(decodeHubVideo(key, payload, listOf(source, source.copy(id = "duplicate"))))
        assertNull(decodeHubVideo(key, JSONObject(payload.toString()).put("source_api", "https://other.test/api"), listOf(source)))
        assertNull(decodeHubVideo(key, JSONObject(payload.toString()).put("video", JSONObject().put("remoteId", "99")), listOf(source)))
    }

    @Test fun snapshotReconciliationRequiresProofAndPreservesPendingChanges() {
        val film = "source:42"
        assertTrue(mayRemoveSyncedWatch(film, 100, 100, emptySet(), emptyList()))
        assertFalse(mayRemoveSyncedWatch(film, 100, null, emptySet(), emptyList()))
        assertFalse(mayRemoveSyncedWatch(film, 101, 100, emptySet(), emptyList()))
        assertFalse(mayRemoveSyncedWatch(film, 100, 100, setOf(film), emptyList()))
        assertFalse(mayRemoveSyncedWatch(film, 100, 100, emptySet(), listOf(SyncEnvelope("progress", "$film:A:0", 101, false, null))))
        assertFalse(mayRemoveSyncedWatch(film, 100, 100, emptySet(), listOf(SyncEnvelope("history", "unknown", 1, true, null))))
        assertTrue(mayRemoveSyncedWatch(film, 100, 100, emptySet(), listOf(SyncEnvelope("progress", "source:43:A:0", 101, false, null))))
    }

    @Test fun invalidatedPartialSnapshotCannotBeUsedForReconciliation() {
        val old = SyncEnvelope("history", "source:42", 1, false, null)
        val interrupted = WatchSnapshotBuffer()
        interrupted.addPage(listOf(old), hasMore = true)
        assertThrows(IllegalStateException::class.java) { interrupted.completeSnapshot() }
        val restarted = WatchSnapshotBuffer()
        restarted.addPage(emptyList(), hasMore = false)
        assertTrue(restarted.completeSnapshot().isEmpty())
    }

}
