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

private class UnresolvedWatchOutbox : IllegalStateException()

internal class HubSyncEngine(
    context: Context,
    private val repository: MacCmsRepository,
    private val tmdbSettings: TmdbSettingsStore,
    private val orbitRepository: OrbitPluginRepository? = null
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

    suspend fun bind(baseUrl: String, pin: String): SyncStatus {
        val normalizedPin = pin.filter(Char::isDigit)
        require(normalizedPin.length == 8) { "请输入完整的 8 位 PIN" }
        mutex.withLock { withContext(Dispatchers.IO) {
            val result = api.bind(baseUrl, normalizedPin, android.os.Build.MODEL.orEmpty())
            retryJob?.cancel()
            synchronized(TmdbAccessGate.lock) {
                database.beginBindingTransition()
                credentials.save(baseUrl.trim().trimEnd('/'), result.getString("device_id"), result.getString("device_token"))
                database.activateBinding(credentials.bindingInstance, result.getString("device_id"))
            }
        } }
        // Existing local data has no proven account ownership. Only changes
        // made after this binding are eligible for automatic push.
        return syncNow()
    }

    internal fun bindingReady(): Boolean = credentials.isBound && !credentials.revoked &&
        database.bindingMatches(credentials.bindingInstance)

    private fun requireBinding(): String {
        check(bindingReady()) { "绑定状态不一致，请重新绑定；旧数据已保留" }
        return credentials.bindingInstance
    }

    suspend fun syncNow(): SyncStatus = actions.pushThenPull()

    suspend fun pushOutbox(trigger: SyncTrigger): SyncStatus {
        if (!credentials.isBound) return SyncStatus("未绑定")
        if (credentials.revoked) return SyncStatus("已解绑", "设备已解绑，请重新绑定")
        val requestedBinding = credentials.bindingInstance
        if (!isSyncing.compareAndSet(false, true)) {
            pendingPush.set(true)
            requestPendingPush()
            return SyncStatus("推送中", "当前推送完成后继续处理待推记录")
        }
        var retryAfter: Long? = null
        val status = try {
            mutex.withLock { withContext(Dispatchers.IO) {
                try {
                    check(requireBinding() == requestedBinding) { "绑定已变更" }
                    val held = pushPending(trigger)
                    if (held > 0) return@withContext SyncStatus("推送部分完成", "已保留 $held 条身份不明确或存在关联冲突的待同步记录")
                    failureCount = 0
                    retryJob?.cancel()
                    SyncStatus("已推送", "待推送记录已发送")
                } catch (_: UnresolvedWatchOutbox) {
                    SyncStatus("推送受阻", "旧观看记录的剧集身份不明确，待同步记录已保留")
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
        if (status.label == "推送失败") scheduleRetry(retryAfter, requestedBinding = requestedBinding)
        return status
    }

    suspend fun pullAndApply(trigger: SyncTrigger): SyncStatus {
        if (!credentials.isBound) return SyncStatus("未绑定")
        if (credentials.revoked) return SyncStatus("已解绑", "设备已解绑，请重新绑定")
        val requestedBinding = credentials.bindingInstance
        if (!isSyncing.compareAndSet(false, true)) {
            pendingPull.set(true)
            pendingPullTrigger.set(trigger)
            requestPendingPush()
            return SyncStatus("拉取已排队", "当前推送或拉取结束后执行")
        }
        val status = try {
            mutex.withLock { withContext(Dispatchers.IO) {
                try {
                    check(requireBinding() == requestedBinding) { "绑定已变更" }
                    try { pullPages(trigger) } catch (failure: HubApiException) {
                        if (failure.failure != HubFailure.ResyncRequired) throw failure
                        rebuildSnapshot(trigger)
                    }
                    val errors = refreshCloudComponents(
                        applySettings = { applyCloudSettings(trigger) },
                        refreshOrbit = { orbitRepository?.refreshPlugins() },
                        verifyBinding = {
                            if (credentials.revoked) throw HubApiException(HubFailure.DeviceRevoked)
                            check(requireBinding() == requestedBinding) { "绑定已变更" }
                        }
                    )
                    if (errors.isNotEmpty()) {
                        val message = errors.joinToString("；")
                        credentials.markFailure(message)
                        SyncStatus("部分更新", message)
                    } else {
                        credentials.markSuccess(System.currentTimeMillis())
                        SyncStatus("已拉取", "云端数据、设置及 Orbit 插件已更新")
                    }
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
        val requestedBinding = credentials.bindingInstance
        if (!isSyncing.compareAndSet(false, true)) {
            pendingFullPush.set(true)
            requestPendingPush()
            return SyncStatus("全量推送已排队", "当前操作结束后执行全量入队与推送")
        }
        return try {
            mutex.withLock { withContext(Dispatchers.IO) {
                check(requireBinding() == requestedBinding) { "绑定已变更" }
                val skippedHistory = 0
                val baselineAt = database.readSyncState("baseline_at", "0").toLongOrNull() ?: 0L
                val pending = database.syncOutboxCount(credentials.generation, baselineAt)
                var sent = 0
                var ignored = 0
                var failed = 0
                while (true) {
                    val current = database.readSyncOutbox(Int.MAX_VALUE, credentials.generation, baselineAt).filter { it.entity != "setting" }
                    if (current.isEmpty()) break
                    val (safe, held) = partitionWatchOutbox(current)
                    if (safe.isEmpty()) return@withContext SyncStatus("推送部分完成", "成功 $sent 条；被忽略 $ignored 条；保留 ${held.size} 条待同步记录；另跳过 $skippedHistory 条本地观看记录")
                    val batch = boundedSyncBatch(safe)
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
                    val counts = confirmedSyncCounts(data, batch)
                        ?: return@withContext SyncStatus("推送失败", "服务器未确认全部记录，待同步记录已保留")
                    val (accepted, batchIgnored) = counts
                    sent += accepted
                    ignored += batchIgnored
                    repository.recordConfirmedWatch(acceptedWatchEnvelopes(data, batch))
                    database.removeSyncOutbox(batch)
                    onProgress(sent + ignored, pending)
                }
                fullPushCompletion(sent, ignored, failed, skippedHistory)
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
                            pendingSinglePushes.drain().forEach { entry -> pushSingle(entry.envelope, entry.trigger, entry.bindingInstance) }
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

    private fun scheduleRetry(retryAfterSeconds: Long?, item: SyncEnvelope? = null, requestedBinding: String = credentials.bindingInstance) {
        retryJob?.cancel()
        val delayMillis = nextRetryDelayMillis(retryAfterSeconds)
        retryJob = retryScope.launch {
            val valid = mutex.withLock {
                if (credentials.bindingInstance != requestedBinding || !bindingReady()) false
                else {
                    database.writeSyncState(mapOf("progress_next_allowed_at" to (System.currentTimeMillis() + delayMillis).toString()))
                    true
                }
            }
            if (!valid) return@launch
            delay(delayMillis)
            if (credentials.bindingInstance != requestedBinding) return@launch
            if (item == null) pushOutbox(SyncTrigger.RETRY)
            else pushSingle(item, SyncTrigger.RETRY, requestedBinding)
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

    suspend fun syncProgressIfDue(key: String?) {
        if (key == null) return
        if (!credentials.isBound || credentials.revoked) return
        val requestedBinding = credentials.bindingInstance
        val seconds = database.readAppSetting("progress_push_interval_seconds")?.toIntOrNull()
        val interval = progressIntervalMillis(seconds)
        val now = System.currentTimeMillis()
        val last = database.readSyncState("last_progress_upload_at").toLongOrNull() ?: 0L
        val nextAllowedAt = database.readSyncState("progress_next_allowed_at").toLongOrNull() ?: 0L
        if (!progressPushAllowed(now, last, interval, nextAllowedAt)) return
        val baseline = database.readSyncState("baseline_at", "0").toLongOrNull() ?: 0L
        val progress = partitionWatchOutbox(database.readSyncOutbox(Int.MAX_VALUE, credentials.generation, baseline))
            .first.firstOrNull { it.entity == "progress" && it.key == key } ?: return
        val currentPosition = (progress.payload?.get("position_ms") as? Number)?.toLong() ?: progress.updatedAt
        val lastUploaded = database.readSyncState("last_uploaded_progress_value:${progress.key}").toLongOrNull()
        if (!progressChanged(lastUploaded, currentPosition)) return
        val valid = mutex.withLock {
            if (!bindingReady() || credentials.bindingInstance != requestedBinding) false
            else { database.writeSyncState(mapOf("last_progress_upload_at" to now.toString())); true }
        }
        if (!valid) return
        val result = pushSingle(progress, SyncTrigger.AUTO_PROGRESS, requestedBinding)
        if (result) mutex.withLock {
            if (bindingReady() && credentials.bindingInstance == requestedBinding) {
                database.writeSyncState(mapOf("progress_next_allowed_at" to "0",
                    "last_uploaded_progress_value:${progress.key}" to currentPosition.toString()))
            }
        }
    }

    private suspend fun pushSingle(item: SyncEnvelope, trigger: SyncTrigger, requestedBinding: String = credentials.bindingInstance): Boolean {
        if (!hasTrustedWatchIdentity(item)) return false
        if (!isSyncing.compareAndSet(false, true)) {
            pendingSinglePushes.put(item, trigger, requestedBinding)
            requestPendingPush()
            return false
        }
        return try {
            mutex.withLock { withContext(Dispatchers.IO) {
                if (requireBinding() != requestedBinding) return@withContext false
                val baseline = database.readSyncState("baseline_at", "0").toLongOrNull() ?: 0L
                val queued = database.readSyncOutbox(Int.MAX_VALUE, credentials.generation, baseline)
                if (partitionWatchOutbox(queued + item).first.none { it === item }) return@withContext false
                if (database.readSyncOutboxItem(item.entity, item.key) != item) return@withContext false
                val reply = api.push(listOf(item), trigger)
                requireSuccess(reply)
                if (confirmedSyncCounts(reply.envelope.data, listOf(item)) == null) return@withContext false
                repository.recordConfirmedWatch(acceptedWatchEnvelopes(reply.envelope.data, listOf(item)))
                database.removeSyncOutbox(listOf(item))
                true
            } }
        } catch (failure: HubApiException) {
            if (failure.failure == HubFailure.DeviceRevoked) {
                mutex.withLock {
                    if (credentials.bindingInstance == requestedBinding) {
                        credentials.markRevoked()
                        retryJob?.cancel()
                    }
                }
            } else {
                val retryAfter = (failure.failure as? HubFailure.Api)?.retryAfterSeconds
                failureCount++
                if (item.entity != "progress") scheduleRetry(retryAfter, item, requestedBinding)
            }
            false
        } catch (_: Exception) {
            failureCount++
            if (item.entity != "progress") scheduleRetry(null, item, requestedBinding)
            false
        } finally {
            isSyncing.set(false)
            if (pendingPush.get() || pendingFullPush.get() || pendingPull.get() || !pendingSinglePushes.isEmpty()) requestPendingPush()
        }
    }

    internal data class RecoveryBinding(val instance: String, val hubUrl: String?, val deviceId: String?)

    internal fun recoveryBinding(): RecoveryBinding? = if (bindingReady())
        RecoveryBinding(credentials.bindingInstance, credentials.hubUrl, credentials.deviceId) else null

    suspend fun quarantinePage(beforeId: Long?): List<QuarantinedSyncRecord> = mutex.withLock { withContext(Dispatchers.IO) {
        if (bindingReady()) {
            val baseline = database.readSyncState("baseline_at", "0").toLongOrNull() ?: 0L
            database.quarantineStaleOutbox(credentials.generation, baseline, credentials.bindingInstance)
            database.quarantineUntrustedWatch(credentials.bindingInstance)
        } else if (database.readSyncState("binding_instance").isNotBlank()) {
            database.beginBindingTransition()
        }
        database.quarantinePage(beforeId)
    } }

    suspend fun restoreQuarantined(record: QuarantinedSyncRecord, selectedBinding: RecoveryBinding?): String? = mutex.withLock { withContext(Dispatchers.IO) {
        if (selectedBinding == null || recoveryBinding() != selectedBinding) return@withContext "绑定已变更，请重新核对当前账号"
        database.restoreQuarantined(record, selectedBinding.instance)
    } }

    fun staleOutboxCount(): Int {
        val baseline = database.readSyncState("baseline_at", "0").toLongOrNull() ?: 0L
        return database.quarantinedOutboxCount() + if (bindingReady()) {
            database.staleSyncOutboxCount(credentials.generation, baseline) + database.untrustedWatchCount(credentials.generation, baseline)
        } else database.syncOutboxTotal()
    }

    suspend fun removeLocalBinding() = mutex.withLock { withContext(Dispatchers.IO) {
        retryJob?.cancel()
        synchronized(TmdbAccessGate.lock) {
            database.beginBindingTransition()
            credentials.clearLocalBinding()
        }
    } }

    suspend fun syncAfterPlaybackPause(key: String?) = pushLatestProgress(key)

    suspend fun syncAfterPlaybackExit(key: String?) = pushLatestProgress(key)

    private suspend fun pushLatestProgress(key: String?) {
        if (key == null) return
        if (!credentials.isBound || credentials.revoked) return
        val requestedBinding = credentials.bindingInstance
        val baselineAt = database.readSyncState("baseline_at", "0").toLongOrNull() ?: 0L
        val progress = partitionWatchOutbox(database.readSyncOutbox(Int.MAX_VALUE, credentials.generation, baselineAt))
            .first.firstOrNull { it.entity == "progress" && it.key == key } ?: return
        val position = (progress.payload?.get("position_ms") as? Number)?.toLong() ?: progress.updatedAt
        if (pushSingle(progress, SyncTrigger.AUTO_PROGRESS, requestedBinding)) mutex.withLock {
            if (bindingReady() && credentials.bindingInstance == requestedBinding) {
                database.writeSyncState(mapOf("last_progress_upload_at" to System.currentTimeMillis().toString(),
                    "last_uploaded_progress_value:${progress.key}" to position.toString()))
            }
        }
    }

    private suspend fun pushPending(trigger: SyncTrigger): Int {
        while (true) {
            val baselineAt = database.readSyncState("baseline_at", "0").toLongOrNull() ?: 0L
            val outbox = database.readSyncOutbox(Int.MAX_VALUE, credentials.generation, baselineAt)
                .filter { it.entity != "setting" }
            if (outbox.isEmpty()) return 0
            val (safe, held) = partitionWatchOutbox(outbox)
            if (safe.isEmpty()) return held.size
            val batch = boundedSyncBatch(safe)
            if (batch.isEmpty()) throw IllegalStateException("同步记录超出请求大小限制")
            val reply = api.push(batch, trigger)
            requireSuccess(reply)
            check(confirmedSyncCounts(reply.envelope.data, batch) != null) { "服务器未确认全部记录，待同步记录已保留" }
            repository.recordConfirmedWatch(acceptedWatchEnvelopes(reply.envelope.data, batch))
            database.removeSyncOutbox(batch)
        }
    }

    private fun acceptedWatchEnvelopes(data: JSONObject?, batch: List<SyncEnvelope>): List<SyncEnvelope> {
        val accepted = data?.optJSONArray("accepted") ?: return emptyList()
        val keys = (0 until accepted.length()).mapNotNull { index ->
            val row = accepted.optJSONObject(index) ?: return@mapNotNull null
            row.optString("entity") to row.optString("key")
        }.toSet()
        return batch.filter { (it.entity to it.key) in keys }
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
            repository.recordConfirmedWatch(envelopes)
            val pageHasMore = data.optBoolean("has_more")
            val nextCursor = nextPageCursor(cursor, data)
            cursor = if (nextCursor != null) nextCursor else data.optLong("next_seq", cursor)
            credentials.writeCursor(cursor, data.optLong("generation", credentials.generation))
            database.writeSyncState(mapOf("cursor" to cursor.toString()))
            if (!pageHasMore) return
        }
    }

    internal suspend fun rebuildSnapshot(
        trigger: SyncTrigger,
        fetchPage: (Long, Long, Int, SyncTrigger) -> HubHttpReply = api::rebuild
    ) {
        val knownGeneration = credentials.generation
        var lastFailure: HubApiException? = null
        repeat(3) {
            var cursor = 0L
            var snapshotSeq: Long? = null
            var snapshotGeneration: Long? = null
            val snapshotBuffer = WatchSnapshotBuffer()
            try {
                while (true) {
                    val reply = fetchPage(knownGeneration, cursor, 500, trigger)
                    requireSuccess(reply)
                    val data = reply.envelope.data ?: JSONObject()
                    val pageSnapshot = data.optLong("snapshot_seq", data.optLong("next_seq", cursor))
                    val pageGeneration = data.optLong("generation", knownGeneration + 1)
                    if (snapshotSeq == null) {
                        snapshotSeq = pageSnapshot
                        snapshotGeneration = pageGeneration
                    } else {
                        require(snapshotSeq == pageSnapshot && snapshotGeneration == pageGeneration) {
                            "snapshot baseline changed during pagination"
                        }
                    }
                    val items = data.optJSONArray("items") ?: JSONArray()
                    val next = nextPageCursor(cursor, data)
                    snapshotBuffer.addPage((0 until items.length()).map { items.getJSONObject(it).toSyncEnvelope() }, next != null)
                    if (next == null) {
                        cursor = data.optLong("next_seq", pageSnapshot)
                        break
                    }
                    require(next > cursor) { "snapshot cursor did not advance" }
                    cursor = next
                }
                val baselineAt = System.currentTimeMillis()
                // Quarantine old queued operations only after a complete snapshot.
                database.writeSyncState(mapOf("generation" to snapshotGeneration.toString(), "baseline_at" to baselineAt.toString()))
                val snapshot = snapshotBuffer.completeSnapshot()
                repository.applyRemoteSyncEnvelopes(snapshot, credentials.deviceId.orEmpty(), isSnapshot = true)
                repository.recordConfirmedWatch(snapshot)
                repository.reconcileConfirmedWatchSnapshot(snapshot)
                credentials.writeCursor(cursor, snapshotGeneration ?: knownGeneration + 1)
                database.writeSyncState(mapOf("cursor" to cursor.toString()))
                return
            } catch (failure: HubApiException) {
                if (failure.failure != HubFailure.ResyncRequired) throw failure
                lastFailure = failure
            }
        }
        throw lastFailure ?: IllegalStateException("快照重建失败")
    }

    private suspend fun applyCloudSettings(trigger: SyncTrigger) {
        val binding = requireBinding()
        // Fail closed before network or either durable store changes. A failed
        // fetch, empty response, process death or credential write stays closed.
        database.invalidateHubSettings()
        val reply = api.settings(trigger)
        requireSuccess(reply)
        val data = reply.envelope.data ?: throw IllegalStateException("云端设置为空")
        check(requireBinding() == binding) { "绑定已变更" }
        tmdbSettings.applyCloudValues(binding, data)
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
