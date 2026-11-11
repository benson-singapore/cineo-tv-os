package com.nova.tv

import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.pow

internal enum class SyncTrigger(val value: String) {
    AUTO_PROGRESS("auto_progress"), AUTO_FAVORITE("auto_favorite"), RETRY("retry"),
    FULL_PUSH("full_push"), LAUNCH_PULL("launch_pull"), MANUAL_PULL("manual_pull")
}

internal class HubSyncActions(
    private val push: suspend (SyncTrigger) -> SyncStatus,
    private val pull: suspend (SyncTrigger) -> SyncStatus,
    private val pushAll: suspend (SyncTrigger) -> SyncStatus
) {
    suspend fun pushOutbox(trigger: SyncTrigger): SyncStatus = push(trigger)
    suspend fun pullAndApply(trigger: SyncTrigger): SyncStatus = pull(trigger)
    suspend fun pushThenPull(): SyncStatus {
        val pushed = pushAll(SyncTrigger.FULL_PUSH)
        if (pushed.label != "推送完成") return pushed
        return pullAndApply(SyncTrigger.MANUAL_PULL)
    }
}

internal data class PendingSinglePush(val envelope: SyncEnvelope, val trigger: SyncTrigger)

internal class PendingSinglePushes {
    private val pending = LinkedHashMap<Pair<String, String>, PendingSinglePush>()

    @Synchronized
    fun put(envelope: SyncEnvelope, trigger: SyncTrigger) {
        pending[envelope.entity to envelope.key] = PendingSinglePush(envelope, trigger)
    }

    @Synchronized
    fun isEmpty(): Boolean = pending.isEmpty()

    @Synchronized
    fun drain(): List<Pair<SyncEnvelope, SyncTrigger>> = pending.values
        .map { it.envelope to it.trigger }
        .also { pending.clear() }
}

internal fun decodeOutboxPayload(raw: String?): Map<String, Any?>? = raw?.let { org.json.JSONObject(it).toMap() }

internal fun progressIntervalMillis(seconds: Int?): Long =
    (seconds?.takeIf { it in setOf(5, 10, 15, 30, 60) } ?: 10) * 1_000L

internal fun progressChanged(lastUploaded: Long?, current: Long): Boolean = lastUploaded == null || lastUploaded != current

internal fun progressPushAllowed(now: Long, lastAttemptAt: Long, intervalMillis: Long, nextAllowedAt: Long): Boolean =
    now - lastAttemptAt >= intervalMillis && now >= nextAllowedAt

internal fun canRemoveOutboxItem(sentUpdatedAt: Long, currentUpdatedAt: Long?): Boolean =
    currentUpdatedAt != null && sentUpdatedAt == currentUpdatedAt

internal fun favoriteDebounceKey(entity: String, key: String): String = "$entity:$key"

internal fun boundedSyncBatch(items: List<SyncEnvelope>, maxBytes: Int = 1_048_576): List<SyncEnvelope> {
    val result = mutableListOf<SyncEnvelope>()
    for (item in items.take(500)) {
        val candidate = result + item
        val bytes = org.json.JSONObject().put("items", org.json.JSONArray().apply { candidate.forEach { put(it.toJson()) } })
            .toString().toByteArray(Charsets.UTF_8).size
        if (bytes > maxBytes) break
        result += item
    }
    return result
}

internal data class SyncEnvelope(
    val entity: String,
    val key: String,
    val updatedAt: Long,
    val deleted: Boolean,
    val payload: Map<String, Any?>?,
    val deviceId: String = "",
    val generation: Long? = null,
    val baselineAt: Long? = null
)

internal sealed class HubFailure {
    data object ResyncRequired : HubFailure()
    data object DeviceRevoked : HubFailure()
    data class Api(val code: Int, val message: String, val retryAfterSeconds: Long? = null) : HubFailure()
    data class Transport(val message: String) : HubFailure()
}

internal data class HubEnvelopeResult(
    val successful: Boolean,
    val data: JSONObject?,
    val failure: HubFailure?
)

internal fun createRebuildRequest(knownGeneration: Long, cursor: Long = 0L, limit: Int = 500): JSONObject {
    require(cursor >= 0L) { "cursor must be non-negative" }
    require(limit in 1..500) { "limit must be between 1 and 500" }
    return JSONObject().put("known_generation", knownGeneration).put("cursor", cursor).put("limit", limit)
}

internal fun isUploadableOutboxItem(item: SyncEnvelope, generation: Long, baselineAt: Long): Boolean =
    item.generation == generation && (item.baselineAt ?: Long.MIN_VALUE) >= baselineAt

internal fun nextPageCursor(currentCursor: Long, page: JSONObject): Long? {
    if (!page.optBoolean("has_more")) return null
    val next = page.optLong("next_seq", currentCursor)
    require(next > currentCursor) { "snapshot page did not advance its cursor" }
    return next
}

internal fun mergeOutbox(existing: List<SyncEnvelope>, incoming: SyncEnvelope): List<SyncEnvelope> {
    val merged = LinkedHashMap<Pair<String, String>, SyncEnvelope>()
    existing.forEach { merged[it.entity to it.key] = it }
    merged[incoming.entity to incoming.key] = incoming
    return merged.values.toList()
}

/** Positive means left wins. The order matches the published API decisions. */
internal fun compareSyncState(left: SyncEnvelope, right: SyncEnvelope): Int =
    compareValues(left.updatedAt, right.updatedAt).takeIf { it != 0 }
        ?: compareValues(left.deleted, right.deleted).takeIf { it != 0 }
        ?: left.deviceId.compareTo(right.deviceId)

internal fun retryDelayMillis(failureCount: Int, retryAfterSeconds: Long?, jitter: Double): Long {
    if (retryAfterSeconds != null) return retryAfterSeconds.coerceAtLeast(0) * 1_000L
    val exponent = (failureCount - 1).coerceAtLeast(0).coerceAtMost(20)
    val base = (2.0.pow(exponent) * 1_000.0).toLong().coerceAtMost(300_000L)
    return (base * (1.0 + jitter.coerceIn(-0.2, 0.2))).toLong().coerceAtMost(300_000L)
}

internal fun parseHubEnvelope(raw: String, httpStatus: Int): HubEnvelopeResult = try {
    val json = JSONObject(raw)
    val code = json.optInt("code", httpStatus)
    val message = json.optString("message", "同步请求失败")
    val data = json.optJSONObject("data")
    when (code) {
        200 -> HubEnvelopeResult(true, data, null)
        40900 -> HubEnvelopeResult(false, null, HubFailure.ResyncRequired)
        40301 -> HubEnvelopeResult(false, null, HubFailure.DeviceRevoked)
        else -> HubEnvelopeResult(false, null, HubFailure.Api(
            code, message, data?.optLong("retry_after_seconds")?.takeIf { it > 0 }
        ))
    }
} catch (_: Exception) {
    HubEnvelopeResult(false, null, HubFailure.Api(httpStatus, "服务器返回格式无效"))
}

internal fun retryMinutes(retryAfterSeconds: Long): Long = ceil(retryAfterSeconds / 60.0).toLong()
