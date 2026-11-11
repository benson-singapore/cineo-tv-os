package com.nova.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubSyncPolicyTest {
    @Test
    fun fakeTransportPushAndPullActionsStayIndependentAndCarryTriggers() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<Pair<String, SyncTrigger>>()
        val transport = HubSyncActions(
            push = { trigger -> calls += "push" to trigger; SyncStatus("已推送") },
            pull = { trigger -> calls += "pull" to trigger; SyncStatus("已拉取") },
            pushAll = { SyncStatus("推送完成") }
        )

        transport.pushOutbox(SyncTrigger.AUTO_PROGRESS)
        assertEquals(listOf("push" to SyncTrigger.AUTO_PROGRESS), calls)
        transport.pullAndApply(SyncTrigger.LAUNCH_PULL)
        assertEquals(listOf("push" to SyncTrigger.AUTO_PROGRESS, "pull" to SyncTrigger.LAUNCH_PULL), calls)
    }

    @Test
    fun fakeTransportRetryPushDoesNotPull() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        val transport = HubSyncActions(
            push = { calls += "push:${it.value}"; SyncStatus("已推送") },
            pull = { calls += "pull:${it.value}"; SyncStatus("已拉取") },
            pushAll = { SyncStatus("推送完成") }
        )

        transport.pushOutbox(SyncTrigger.RETRY)
        assertEquals(listOf("push:retry"), calls)
    }

    @Test
    fun fakeTransportCombinedActionStopsAfterFailedPushAndUsesManualPullTrigger() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        val transport = HubSyncActions(
            push = { calls += "push:${it.value}"; SyncStatus("推送失败") },
            pull = { calls += "pull:${it.value}"; SyncStatus("已拉取") },
            pushAll = { calls += "all:${it.value}"; SyncStatus("推送失败") }
        )

        assertEquals("推送失败", transport.pushThenPull().label)
        assertEquals(listOf("all:full_push"), calls)
    }

    @Test
    fun combinedActionUsesFullSnapshotPushBeforePull() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<String>()
        val actions = HubSyncActions(
            push = { calls += "incremental:${it.value}"; SyncStatus("已推送") },
            pull = { calls += "pull:${it.value}"; SyncStatus("已拉取") },
            pushAll = { calls += "all:${it.value}"; SyncStatus("推送完成") }
        )
        actions.pushThenPull()
        assertEquals(listOf("all:full_push", "pull:manual_pull"), calls)
    }

    @Test
    fun outboxPayloadJsonDecodesForFavoritePush() {
        assertEquals(mapOf("title" to "Saved", "category" to "movie"), decodeOutboxPayload("{\"title\":\"Saved\",\"category\":\"movie\"}"))
    }

    @Test
    fun progressIntervalAcceptsOnlySupportedValuesAndDefaultsToTenSeconds() {
        assertEquals(10_000L, progressIntervalMillis(null))
        assertEquals(5_000L, progressIntervalMillis(5))
        assertEquals(60_000L, progressIntervalMillis(60))
        assertEquals(10_000L, progressIntervalMillis(7))
    }

    @Test
    fun progressPushRequiresChangedProgressValue() {
        assertTrue(progressChanged(lastUploaded = 12_000L, current = 12_001L))
        assertFalse(progressChanged(lastUploaded = 12_000L, current = 12_000L))
        assertTrue(progressChanged(lastUploaded = null, current = 0L))
    }

    @Test
    fun syncTriggerNamesMatchPublishedContract() {
        assertEquals("auto_progress", SyncTrigger.AUTO_PROGRESS.value)
        assertEquals("auto_favorite", SyncTrigger.AUTO_FAVORITE.value)
        assertEquals("retry", SyncTrigger.RETRY.value)
        assertEquals("full_push", SyncTrigger.FULL_PUSH.value)
        assertEquals("launch_pull", SyncTrigger.LAUNCH_PULL.value)
        assertEquals("manual_pull", SyncTrigger.MANUAL_PULL.value)
    }
    @Test
    fun syncBatchNeverExceedsFiveHundredItemsOrOneMegabyte() {
        val items = (1..501).map { index -> SyncEnvelope("favorite", "k$index", index.toLong(), false, mapOf("name" to "video$index")) }
        assertEquals(500, boundedSyncBatch(items).size)
        assertTrue(boundedSyncBatch(items, maxBytes = 100).size < items.size)
    }

    @Test
    fun outboxMergesLatestEnvelopeByEntityAndKey() {
        val old = SyncEnvelope("favorite", "site:movie", 10, false, mapOf("title" to "old"))
        val newer = SyncEnvelope("favorite", "site:movie", 11, false, mapOf("title" to "new"))
        val other = SyncEnvelope("favorite", "site:other", 12, false, mapOf("title" to "other"))

        assertEquals(listOf(newer, other), mergeOutbox(listOf(old, other), newer))
    }

    @Test
    fun conflictUsesTimestampThenTombstoneThenDeviceId() {
        val live = SyncEnvelope("favorite", "k", 20, false, emptyMap(), "A")
        val tombstone = live.copy(deleted = true, deviceId = "A")
        val higherDevice = live.copy(deviceId = "B")

        assertTrue(compareSyncState(tombstone, live) > 0)
        assertTrue(compareSyncState(higherDevice, live) > 0)
        assertTrue(compareSyncState(live.copy(updatedAt = 21), tombstone) > 0)
    }

    @Test
    fun retryHonorsServerDelayAndCapsJitteredBackoff() {
        assertEquals(45_000L, retryDelayMillis(failureCount = 2, retryAfterSeconds = 45, jitter = 0.0))
        assertEquals(300_000L, retryDelayMillis(failureCount = 20, retryAfterSeconds = null, jitter = 0.2))
    }

    @Test
    fun responseEnvelopeUsesCodeRatherThanHttpStatus() {
        assertTrue(parseHubEnvelope("{\"code\":200,\"message\":\"ok\",\"data\":{}}", 503).successful)
        assertFalse(parseHubEnvelope("{\"code\":40001,\"message\":\"bad\",\"data\":null}", 200).successful)
    }

    @Test
    fun onlyCurrentGenerationBaselineOutboxItemsAreUploadable() {
        val baseline = SyncEnvelope("favorite", "k", 20, false, emptyMap(), generation = 3, baselineAt = 100L)
        assertTrue(isUploadableOutboxItem(baseline, generation = 3, baselineAt = 100L))
        assertFalse(isUploadableOutboxItem(baseline.copy(generation = 2), generation = 3, baselineAt = 100L))
        assertFalse(isUploadableOutboxItem(baseline.copy(baselineAt = 99L), generation = 3, baselineAt = 100L))
    }

    @Test
    fun rebuildRequestCarriesGenerationCursorAndBoundedLimit() {
        val request = createRebuildRequest(knownGeneration = 4L, cursor = 500L, limit = 500)
        assertEquals(4L, request.getLong("known_generation"))
        assertEquals(500L, request.getLong("cursor"))
        assertEquals(500, request.getInt("limit"))
    }

    @Test
    fun rebuildUsesNextSequenceWhileSnapshotHasMorePages() {
        val page = org.json.JSONObject("{\"next_seq\":500,\"snapshot_seq\":1200,\"has_more\":true}")

        assertEquals(500L, nextPageCursor(currentCursor = 0L, page = page))
        assertEquals(1000L, nextPageCursor(currentCursor = 500L, page = page.put("next_seq", 1000)))
        assertEquals(null, nextPageCursor(currentCursor = 1000L, page = page.put("has_more", false)))
    }

    @Test
    fun resyncRequiredIsNotTreatedAsOrdinaryFailure() {
        assertEquals(HubFailure.ResyncRequired, parseHubEnvelope("{\"code\":40900,\"message\":\"resync\",\"data\":null}", 409).failure)
    }

    @Test
    fun progressIsBlockedByRetryDeadlineEvenWhenIntervalElapsed() {
        assertFalse(progressPushAllowed(now = 20_000, lastAttemptAt = 0, intervalMillis = 10_000, nextAllowedAt = 25_000))
        assertTrue(progressPushAllowed(now = 25_000, lastAttemptAt = 0, intervalMillis = 10_000, nextAllowedAt = 25_000))
    }

    @Test
    fun outboxAcknowledgementRequiresMatchingTimestamp() {
        assertTrue(canRemoveOutboxItem(sentUpdatedAt = 10, currentUpdatedAt = 10))
        assertFalse(canRemoveOutboxItem(sentUpdatedAt = 10, currentUpdatedAt = 11))
    }

    @Test
    fun pendingSinglePushesKeepEachRecordAndOriginalTrigger() {
        val pending = PendingSinglePushes()
        val progress = SyncEnvelope("progress", "movie-a", 10, false, mapOf("position_ms" to 10))
        val favorite = SyncEnvelope("favorite", "movie-b", 11, true, null)

        pending.put(progress, SyncTrigger.AUTO_PROGRESS)
        pending.put(favorite, SyncTrigger.AUTO_FAVORITE)

        assertEquals(listOf(progress to SyncTrigger.AUTO_PROGRESS, favorite to SyncTrigger.AUTO_FAVORITE), pending.drain())
        assertTrue(pending.drain().isEmpty())
    }

    @Test
    fun pendingSinglePushForSameKeyKeepsLatestEnvelopeAndTrigger() {
        val pending = PendingSinglePushes()
        pending.put(SyncEnvelope("favorite", "k", 10, false, mapOf("title" to "old")), SyncTrigger.AUTO_FAVORITE)
        val latest = SyncEnvelope("favorite", "k", 11, true, null)
        pending.put(latest, SyncTrigger.AUTO_FAVORITE)

        assertEquals(listOf(latest to SyncTrigger.AUTO_FAVORITE), pending.drain())
    }

    @Test
    fun debounceKeysAreScopedPerEntityAndRecord() {
        assertEquals("favorite:movie-a", favoriteDebounceKey("favorite", "movie-a"))
        assertEquals("favorite:movie-b", favoriteDebounceKey("favorite", "movie-b"))
    }
}
