package com.nova.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.min

internal class HubSyncEngine(
    context: Context,
    private val repository: MacCmsRepository,
    private val tmdbSettings: TmdbSettingsStore
) {
    val credentials = DeviceCredentialStore(context.applicationContext)
    private val api = HubSyncApi(credentials)
    private val database = CineoDatabase(context.applicationContext)
    private val mutex = Mutex()
    private val isSyncing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val retryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var retryJob: kotlinx.coroutines.Job? = null
    private var failureCount = 0
    private val pendingPush = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pendingFullPush = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pendingPull = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pendingPullTrigger = java.util.concurrent.atomic.AtomicReference<SyncTrigger?>(null)
    private val pendingSinglePushes = PendingSinglePushes()
    private var pendingPushJob: kotlinx.coroutines.Job? = null
    private val favoritePushJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val actions = HubSyncActions(::pushOutbox, ::pullAndApply) { trigger -> pushAll(trigger = trigger) }

    suspend fun bind(baseUrl: String, pin: String): SyncStatus = withContext(Dispatchers.IO) {
        val normalizedPin = pin.filter(Char::isDigit)
        require(normalizedPin.length == 8) { "请输入完整的 8 位 PIN" }
        val result = api.bind(baseUrl, normalizedPin, android.os.Build.MODEL.orEmpty())
        credentials.save(baseUrl.trim().trimEnd('/'), result.getString("device_id"), result.getString("device_token"))
        database.writeSyncState(mapOf("bound_at" to System.currentTimeMillis().toString(), "device_id" to result.getString("device_id"), "generation" to "0", "baseline_at" to "0"))
        repository.enqueueLocalSyncSnapshot()
        syncNow()
    }

    suspend fun syncNow(): SyncStatus = actions.pushThenPull()

    suspend fun pushOutbox(trigger: SyncTrigger): SyncStatus {
        if (!credentials.isBound) return SyncStatus("未绑定")
        if (credentials.revoked) return SyncStatus("已解绑", "设备已解绑，请重新绑定")
        if (!isSyncing.compareAndSet(false, true)) {
            pendingPush.set(true)
            requestPendingPush()
            return SyncStatus("推送中", "当前推送完成后继续处理待推记录")
        }
        var retryAfter: Long? = null
        val status = try {
            mutex.withLock { withContext(Dispatchers.IO) {
                try {
                    pushPending(trigger)
                    failureCount = 0
                    retryJob?.cancel()
                    SyncStatus("已推送", "待推送记录已发送")
                } catch (failure: HubApiException) {
                    when (val reason = failure.failure) {
                        HubFailure.DeviceRevoked -> {
                            credentials.markRevoked()
                            retryJob?.cancel()
                            SyncStatus("已解绑", "设备已解绑，请重新绑定")
                        }
                        is HubFailure.Api -> {
                            retryAfter = reason.retryAfterSeconds
                            failureCount++
                            SyncStatus("推送失败", userMessage(reason))
                        }
                        else -> {
                            failureCount++
                            SyncStatus("推送失败", "Hub 暂不可用，本地更改仍保留")
                        }
                    }
                } catch (_: Exception) {
                    failureCount++
                    SyncStatus("推送失败", "Hub 暂不可用，本地更改仍保留")
                }
            } }
        } finally {
            isSyncing.set(false)
            if (pendingPush.get() || pendingFullPush.get() || pendingPull.get() || !pendingSinglePushes.isEmpty()) requestPendingPush()
        }
        if (status.label == "推送失败") scheduleRetry(retryAfter)
        return status
    }

    suspend fun pullAndApply(trigger: SyncTrigger): SyncStatus {
        if (!credentials.isBound) return SyncStatus("未绑定")
        if (credentials.revoked) return SyncStatus("已解绑", "设备已解绑，请重新绑定")
        if (!isSyncing.compareAndSet(false, true)) {
            pendingPull.set(true)
            pendingPullTrigger.set(trigger)
            requestPendingPush()
            return SyncStatus("拉取已排队", "当前推送或拉取结束后执行")
        }
        val status = try {
            mutex.withLock { withContext(Dispatchers.IO) {
                try {
                    try { pullPages(trigger) } catch (failure: HubApiException) {
                        if (failure.failure != HubFailure.ResyncRequired) throw failure
                        rebuildSnapshot(trigger)
                    }
                    applyCloudSettings(trigger)
                    credentials.markSuccess(System.currentTimeMillis())
                    SyncStatus("已拉取", "云端数据与设置已应用")
                } catch (failure: HubApiException) {
                    if (failure.failure == HubFailure.DeviceRevoked) credentials.markRevoked()
                    val message = (failure.failure as? HubFailure.Api)?.let(::userMessage) ?: "云端拉取失败"
                    credentials.markFailure(message)
                    SyncStatus("拉取失败", message)
                } catch (_: Exception) {
                    credentials.markFailure("Hub 暂不可用")
                    SyncStatus("拉取失败", "Hub 暂不可用，请稍后手动重试")
                }
            } }
        } finally {
            isSyncing.set(false)
            if (pendingPush.get() || pendingFullPush.get() || pendingPull.get() || !pendingSinglePushes.isEmpty()) requestPendingPush()
        }
        return status
    }

    suspend fun pushAll(onProgress: (Int, Int) -> Unit = { _, _ -> }, trigger: SyncTrigger = SyncTrigger.FULL_PUSH): SyncStatus {
        if (!isSyncing.compareAndSet(false, true)) {
            pendingFullPush.set(true)
            requestPendingPush()
            return SyncStatus("全量推送已排队", "当前操作结束后执行全量入队与推送")
        }
        return try {
            mutex.withLock { withContext(Dispatchers.IO) {
                repository.enqueueLocalSyncSnapshot()
                val baselineAt = database.readSyncState("baseline_at", "0").toLongOrNull() ?: 0L
                val pending = database.syncOutboxCount(credentials.generation, baselineAt)
                var sent = 0
                var ignored = 0
                var failed = 0
                while (true) {
                    val current = database.readSyncOutbox(500, credentials.generation, baselineAt).filter { it.entity != "setting" }
                    if (current.isEmpty()) break
                    val batch = boundedSyncBatch(current)
                    if (batch.isEmpty()) return@withContext SyncStatus("推送失败", "单条记录超过 1 MB 请求限制")
                    val reply = try {
                        api.push(batch, trigger).also(::requireSuccess)
                    } catch (failure: Exception) {
                        failed = pending - sent - ignored
                        failureCount++
                        val hubFailure = (failure as? HubApiException)?.failure
                        if (hubFailure == HubFailure.DeviceRevoked) {
                            credentials.markRevoked()
                            retryJob?.cancel()
                        } else scheduleRetry((hubFailure as? HubFailure.Api)?.retryAfterSeconds)
                        val message = when (hubFailure) {
                            HubFailure.DeviceRevoked -> "设备已解绑，请重新绑定"
                            is HubFailure.Api -> userMessage(hubFailure)
                            else -> failure.message?.take(120) ?: "Hub 暂不可用"
                        }
                        return@withContext SyncStatus("推送失败", "成功 $sent 条；被忽略 $ignored 条；失败 $failed 条：$message")
                    }
                    val data = reply.envelope.data
                    val accepted = data?.optJSONArray("accepted")?.length() ?: batch.size
                    val batchIgnored = data?.optJSONArray("ignored")?.length() ?: 0
                    sent += accepted
                    ignored += batchIgnored
                    database.removeSyncOutbox(batch)
                    onProgress(sent + ignored, pending)
                }
                SyncStatus("推送完成", "成功 $sent 条；被忽略 $ignored 条；失败 $failed 条")
            } }
        } catch (failure: Exception) {
            SyncStatus("推送失败", failure.message?.take(160) ?: "推送全部失败")
        } finally {
            isSyncing.set(false)
            if (pendingPush.get() || pendingFullPush.get() || pendingPull.get() || !pendingSinglePushes.isEmpty()) requestPendingPush()
        }
    }

    private fun requestPendingPush() {
        if (pendingPushJob?.isActive == true) return
        pendingPushJob = retryScope.launch {
            try {
                delay(250L)
                if (!isSyncing.get()) {
                    when {
                        pendingFullPush.getAndSet(false) -> pushAll(trigger = SyncTrigger.FULL_PUSH)
                        pendingPull.getAndSet(false) -> pullAndApply(pendingPullTrigger.getAndSet(null) ?: SyncTrigger.MANUAL_PULL)
                        !pendingSinglePushes.isEmpty() -> {
                            pendingSinglePushes.drain().forEach { (item, trigger) -> pushSingle(item, trigger) }
                        }
                        pendingPush.getAndSet(false) -> pushOutbox(SyncTrigger.RETRY)
                    }
                }
            } finally {
                pendingPushJob = null
                if (pendingFullPush.get() || pendingPull.get() || pendingPush.get() || !pendingSinglePushes.isEmpty()) requestPendingPush()
            }
        }
    }

    private fun scheduleRetry(retryAfterSeconds: Long?) {
        retryJob?.cancel()
        val delayMillis = nextRetryDelayMillis(retryAfterSeconds)
        database.writeSyncState(mapOf("progress_next_allowed_at" to (System.currentTimeMillis() + delayMillis).toString()))
        retryJob = retryScope.launch {
            delay(delayMillis)
            pushOutbox(SyncTrigger.RETRY)
        }
    }

    fun scheduleFavoritePush(key: String) {
        val jobKey = favoriteDebounceKey("favorite", key)
        favoritePushJobs.remove(jobKey)?.cancel()
        favoritePushJobs[jobKey] = retryScope.launch {
            try {
                delay(2_000L)
                if (!credentials.isBound || credentials.revoked) return@launch
                val baselineAt = database.readSyncState("baseline_at", "0").toLongOrNull() ?: 0L
                val favorite = database.readSyncOutboxItem("favorite", key) ?: return@launch
                if (favorite.generation != credentials.generation || (favorite.baselineAt ?: Long.MIN_VALUE) < baselineAt) return@launch
                pushSingle(favorite, SyncTrigger.AUTO_FAVORITE)
            } finally { favoritePushJobs.remove(jobKey, kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]) }
        }
    }

    fun progressIntervalSeconds(): Int = database.readAppSetting("progress_push_interval_seconds")?.toIntOrNull()
        ?.takeIf { it in setOf(5, 10, 15, 30, 60) } ?: 10

    fun setProgressIntervalSeconds(seconds: Int) {
        require(seconds in setOf(5, 10, 15, 30, 60))
        database.writeAppSettings(mapOf("progress_push_interval_seconds" to seconds.toString()))
    }

    suspend fun syncProgressIfDue() {
        if (!credentials.isBound || credentials.revoked) return
        val seconds = database.readAppSetting("progress_push_interval_seconds")?.toIntOrNull()
        val interval = progressIntervalMillis(seconds)
        val now = System.currentTimeMillis()
        val last = database.readSyncState("last_progress_upload_at").toLongOrNull() ?: 0L
        val nextAllowedAt = database.readSyncState("progress_next_allowed_at").toLongOrNull() ?: 0L
        if (!progressPushAllowed(now, last, interval, nextAllowedAt)) return
        val progress = database.readLatestProgressOutbox(credentials.generation,
            database.readSyncState("baseline_at", "0").toLongOrNull() ?: 0L) ?: return
        val currentPosition = (progress.payload?.get("position_ms") as? Number)?.toLong() ?: progress.updatedAt
        val lastUploaded = database.readSyncState("last_uploaded_progress_value:${progress.key}").toLongOrNull()
        if (!progressChanged(lastUploaded, currentPosition)) return
        database.writeSyncState(mapOf("last_progress_upload_at" to now.toString()))
        val result = pushSingle(progress, SyncTrigger.AUTO_PROGRESS)
        if (result) {
            database.writeSyncState(mapOf("progress_next_allowed_at" to "0",
                "last_uploaded_progress_value:${progress.key}" to currentPosition.toString()))
        }
    }

    private suspend fun pushSingle(item: SyncEnvelope, trigger: SyncTrigger): Boolean {
        if (!isSyncing.compareAndSet(false, true)) {
            pendingSinglePushes.put(item, trigger)
            requestPendingPush()
            return false
        }
        return try {
            mutex.withLock { withContext(Dispatchers.IO) {
                val reply = api.push(listOf(item), trigger)
                requireSuccess(reply)
                database.removeSyncOutbox(listOf(item))
                true
            } }
        } catch (failure: HubApiException) {
            if (failure.failure == HubFailure.DeviceRevoked) {
                credentials.markRevoked()
                retryJob?.cancel()
            } else {
                val retryAfter = (failure.failure as? HubFailure.Api)?.retryAfterSeconds
                failureCount++
                scheduleRetry(retryAfter)
            }
            false
        } catch (_: Exception) {
            failureCount++
            scheduleRetry(null)
            false
        } finally {
            isSyncing.set(false)
            if (pendingPush.get() || pendingFullPush.get() || pendingPull.get() || !pendingSinglePushes.isEmpty()) requestPendingPush()
        }
    }

    fun staleOutboxCount(): Int {
        val baseline = database.readSyncState("baseline_at", "0").toLongOrNull() ?: 0L
        return database.staleSyncOutboxCount(credentials.generation, baseline)
    }

    fun discardStaleOutbox(): Int {
        val baseline = database.readSyncState("baseline_at", "0").toLongOrNull() ?: 0L
        return database.discardStaleSyncOutbox(credentials.generation, baseline)
    }

    fun removeLocalBinding() {
        retryJob?.cancel()
        credentials.clearLocalBinding()
        database.clearSyncOutbox()
        database.clearSyncBindingState()
        database.clearHubManagedSettings()
    }

    suspend fun syncAfterPlaybackPause() = pushLatestProgress()

    suspend fun syncAfterPlaybackExit() = pushLatestProgress()

    private suspend fun pushLatestProgress() {
        if (!credentials.isBound || credentials.revoked) return
        val baselineAt = database.readSyncState("baseline_at", "0").toLongOrNull() ?: 0L
        val progress = database.readLatestProgressOutbox(credentials.generation, baselineAt) ?: return
        val position = (progress.payload?.get("position_ms") as? Number)?.toLong() ?: progress.updatedAt
        if (pushSingle(progress, SyncTrigger.AUTO_PROGRESS)) {
            database.writeSyncState(mapOf("last_progress_upload_at" to System.currentTimeMillis().toString(),
                "last_uploaded_progress_value:${progress.key}" to position.toString()))
        }
    }

    private suspend fun pushPending(trigger: SyncTrigger) {
        while (true) {
            val baselineAt = database.readSyncState("baseline_at", "0").toLongOrNull() ?: 0L
            val outbox = database.readSyncOutbox(500, credentials.generation, baselineAt)
                .filter { it.entity != "setting" }
            if (outbox.isEmpty()) return
            val batch = boundedSyncBatch(outbox)
            if (batch.isEmpty()) throw IllegalStateException("同步记录超出请求大小限制")
            val reply = api.push(batch, trigger)
            requireSuccess(reply)
            database.removeSyncOutbox(batch)
        }
    }

    private suspend fun pullPages(trigger: SyncTrigger) {
        var cursor = credentials.lastCursor
        while (true) {
            val reply = api.pull(cursor, trigger)
            requireSuccess(reply)
            val data = reply.envelope.data ?: JSONObject()
            val items = data.optJSONArray("items") ?: JSONArray()
            val envelopes = (0 until items.length()).map { items.getJSONObject(it).toSyncEnvelope() }
            repository.applyRemoteSyncEnvelopes(envelopes, credentials.deviceId.orEmpty())
            val pageHasMore = data.optBoolean("has_more")
            val nextCursor = nextPageCursor(cursor, data)
            cursor = if (nextCursor != null) nextCursor else data.optLong("next_seq", cursor)
            credentials.writeCursor(cursor, data.optLong("generation", credentials.generation))
            database.writeSyncState(mapOf("cursor" to cursor.toString()))
            if (!pageHasMore) return
        }
    }

    private suspend fun rebuildSnapshot(trigger: SyncTrigger) {
        val knownGeneration = credentials.generation
        var cursor = 0L
        var snapshotSeq: Long? = null
        var snapshotGeneration: Long? = null
        var baselineAt: Long? = null
        var finalCursor = 0L
        while (true) {
            val reply = api.rebuild(knownGeneration, cursor, 500, trigger)
            requireSuccess(reply)
            val data = reply.envelope.data ?: JSONObject()
            val pageSnapshot = data.optLong("snapshot_seq", data.optLong("next_seq", cursor))
            val pageGeneration = data.optLong("generation", knownGeneration + 1)
            if (snapshotSeq == null) {
                snapshotSeq = pageSnapshot
                snapshotGeneration = pageGeneration
                baselineAt = System.currentTimeMillis()
                database.writeSyncState(mapOf(
                    "generation" to pageGeneration.toString(),
                    "baseline_at" to baselineAt.toString()
                ))
            } else {
                require(snapshotSeq == pageSnapshot && snapshotGeneration == pageGeneration) {
                    "snapshot baseline changed during pagination"
                }
            }
            val items = data.optJSONArray("items") ?: JSONArray()
            repository.applyRemoteSyncEnvelopes(
                (0 until items.length()).map { items.getJSONObject(it).toSyncEnvelope() },
                credentials.deviceId.orEmpty(),
                isSnapshot = true
            )
            finalCursor = data.optLong("next_seq", pageSnapshot)
            val next = nextPageCursor(cursor, data)
            if (next == null) break
            cursor = next
        }
        val completedBaselineAt = requireNotNull(baselineAt)
        credentials.writeCursor(finalCursor, snapshotGeneration ?: knownGeneration + 1)
        database.writeSyncState(mapOf(
            "cursor" to finalCursor.toString(),
            "generation" to (snapshotGeneration ?: knownGeneration + 1).toString(),
            "baseline_at" to completedBaselineAt.toString()
        ))
        repository.enqueueLocalSyncSnapshot()
    }

    private suspend fun applyCloudSettings(trigger: SyncTrigger) {
        val reply = api.settings(trigger)
        requireSuccess(reply)
        val data = reply.envelope.data ?: return
        if (!data.isNull("tmdb_key")) {
            val key = data.optString("tmdb_key").takeIf(String::isNotBlank)
            if (key != null) tmdbSettings.saveToken(key) else tmdbSettings.clearToken()
        } else tmdbSettings.clearToken()
        val prefs = data.optJSONObject("preferences") ?: JSONObject()
        database.writeAppSettings(mapOf(
            "hub.tmdb_language" to prefs.optString("tmdb_language", "zh-CN"),
            "hub.include_adult" to prefs.optBoolean("include_adult", false).toString()
        ))
    }

    private fun requireSuccess(reply: HubHttpReply) {
        if (!reply.envelope.successful) throw HubApiException(reply.envelope.failure ?: HubFailure.Api(reply.status, "同步失败"))
    }

    private fun userMessage(failure: HubFailure.Api): String = when (failure.code) {
        40001 -> "PIN 无效或已过期"
        42901 -> "操作过于频繁，请 ${retryMinutes(failure.retryAfterSeconds ?: 60)} 分钟后重试"
        42902 -> "同步配额已满"
        401, 40100 -> "设备凭证无效，请重新绑定"
        40301 -> "设备已解绑，请重新绑定"
        else -> failure.message.takeIf(String::isNotBlank)?.take(180) ?: "同步请求失败"
    }

    fun nextRetryDelayMillis(retryAfterSeconds: Long? = null): Long = retryDelayMillis(
        failureCount.coerceAtLeast(1), retryAfterSeconds,
        ThreadLocalRandom.current().nextDouble(-0.2, 0.200001)
    )

    companion object {
        internal fun formatLocalTime(time: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(time))
    }
}
