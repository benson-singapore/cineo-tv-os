package com.nova.tv

import android.content.Context
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

data class VideoSource(
    val id: String,
    val name: String,
    val api: String,
    val detailUrl: String = "",
    val enabled: Boolean = true,
    val isAdult: Boolean = false,
    val isDefault: Boolean = false,
    val lastLatencyMs: Long? = null,
    val lastError: String? = null
)

data class RemoteVideo(
    val id: String,
    val sourceId: String,
    val sourceName: String,
    val remoteId: String,
    val title: String,
    val category: String,
    val year: String,
    val description: String,
    val posterUrl: String,
    val rating: String
)

data class PlaybackEpisode(val title: String, val url: String)
data class PlaybackLine(val name: String, val episodes: List<PlaybackEpisode>)
data class RemoteVideoDetail(val video: RemoteVideo, val lines: List<PlaybackLine>)
data class SearchResult(val videos: List<RemoteVideo>, val errors: List<String>)
data class VideoCategory(val id: String, val name: String, val parentId: String? = null)
data class VideoCatalogPage(val videos: List<RemoteVideo>, val page: Int, val hasMore: Boolean)
data class WatchRecord(
    val video: RemoteVideo,
    val lineName: String,
    val episode: PlaybackEpisode,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
) {
    val progress: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val isComplete: Boolean get() = durationMs > 0 && positionMs >= durationMs - 45_000
}
data class ImportResult(val imported: Int, val skipped: Int, val error: String? = null)
private data class SourceSearchReply(val videos: List<RemoteVideo>, val error: String? = null)

/** Source IDs and episode URLs change when switching providers. */
internal fun sameHistoryVideo(first: RemoteVideo, second: RemoteVideo): Boolean =
    first.id == second.id || (
        first.title.filter { it.isLetterOrDigit() }.lowercase().let { title ->
            title.isNotEmpty() && title == second.title.filter { it.isLetterOrDigit() }.lowercase()
        } && first.year.trim() == second.year.trim()
    )

internal fun matchingHistoryEpisode(record: WatchRecord, episodes: List<PlaybackEpisode>): PlaybackEpisode? {
    episodes.firstOrNull { it.url == record.episode.url }?.let { return it }
    episodes.firstOrNull { it.title.trim() == record.episode.title.trim() }?.let { return it }
    val numberPattern = Regex("^(?:第\\s*)?(\\d+)(?:\\s*[集话期])?$")
    val number = numberPattern.matchEntire(record.episode.title.trim())?.groupValues?.get(1)?.toIntOrNull()
    if (number != null) {
        episodes.firstOrNull {
            numberPattern.matchEntire(it.title.trim())?.groupValues?.get(1)?.toIntOrNull() == number
        }?.let { return it }
    }
    return episodes.singleOrNull()?.takeIf {
        number == null && !Regex("\\d+").containsMatchIn(record.episode.title)
    }
}

/** Local-first MacCMS API source configuration and discovery. */
class MacCmsRepository(context: Context) {
    private val database = CineoDatabase(context.applicationContext)
    private val legacyPreferences = context.getSharedPreferences("cineo_tv_sources", Context.MODE_PRIVATE)

    init {
        migrateLegacyVideoData()
        removeDuplicateWatchHistory()
    }

    fun sources(): List<VideoSource> {
        val cursor = database.readableDatabase.query(
            SOURCES_TABLE,
            null,
            null,
            null,
            null,
            null,
            "is_default DESC, name COLLATE NOCASE ASC"
        )
        return cursor.use { rows -> buildList { while (rows.moveToNext()) add(sourceFromCursor(rows)) } }
    }

    fun defaultSource(): VideoSource? {
        val enabled = sources().filter { it.enabled }
        return enabled.firstOrNull { it.isDefault } ?: enabled.firstOrNull()
    }

    fun favorites(): List<RemoteVideo> {
        val cursor = database.readableDatabase.query(
            FAVORITES_TABLE,
            arrayOf("video_json"),
            null,
            null,
            null,
            null,
            "added_at DESC"
        )
        return cursor.use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    runCatching { videoFromJson(JSONObject(rows.getString(0))) }.getOrNull()?.let(::add)
                }
            }
        }
    }

    fun isFavorite(videoId: String): Boolean {
        val cursor = database.readableDatabase.query(
            FAVORITES_TABLE,
            arrayOf("video_id"),
            "video_id = ?",
            arrayOf(videoId),
            null,
            null,
            null,
            "1"
        )
        return cursor.use { it.moveToFirst() }
    }

    internal fun favoriteSyncKey(video: RemoteVideo): String? {
        val source = sources().firstOrNull { it.id == video.sourceId } ?: return null
        return "${hubSourceKey(source.api)}:${syncEncode(video.remoteId)}"
    }

    fun setFavorite(video: RemoteVideo, favorite: Boolean) {
        val db = database.writableDatabase
        val source = sources().firstOrNull { it.id == video.sourceId }
        val key = "${source?.let { hubSourceKey(it.api) } ?: video.sourceId}:${syncEncode(video.remoteId)}"
        val now = System.currentTimeMillis()
        if (!favorite) {
            db.delete(FAVORITES_TABLE, "video_id = ?", arrayOf(video.id))
            database.enqueueSyncEnvelope("favorite", key, now, deleted = true, payloadJson = null)
            return
        }
        val values = ContentValues().apply {
            put("video_id", video.id)
            put("video_json", videoToJson(video).toString())
            put("added_at", now)
        }
        db.insertWithOnConflict(FAVORITES_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        val payload = JSONObject().put("video", videoToJson(video)).put("source_api", source?.api.orEmpty()).put("added_at", now)
        database.enqueueSyncEnvelope("favorite", key, now, deleted = false, payloadJson = payload.toString())
    }

    fun watchHistory(): List<WatchRecord> {
        val cursor = database.readableDatabase.query(
            WATCH_HISTORY_TABLE,
            arrayOf("record_json"),
            null,
            null,
            null,
            null,
            "updated_at DESC"
        )
        return cursor.use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    runCatching { watchRecordFromJson(JSONObject(rows.getString(0))) }.getOrNull()?.let(::add)
                }
            }
        }
    }

    fun watchRecord(video: RemoteVideo): WatchRecord? =
        watchHistory().firstOrNull { sameHistoryVideo(it.video, video) }

    private fun removeDuplicateWatchHistory(){
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val retained = mutableListOf<WatchRecord>()
            watchHistory().forEach { record ->
                if (retained.any { sameHistoryVideo(it.video, record.video) }) {
                    db.delete(WATCH_HISTORY_TABLE, "video_id = ?", arrayOf(record.video.id))
                } else retained += record
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun saveWatchProgress(
        video: RemoteVideo,
        lineName: String,
        episode: PlaybackEpisode,
        positionMs: Long,
        durationMs: Long
    ) {
        val now = System.currentTimeMillis()
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val previous = watchRecord(video)?.takeIf {
                matchingHistoryEpisode(it, listOf(episode)) != null
            }
            val record = WatchRecord(
                video, lineName, episode, positionMs.coerceAtLeast(0),
                durationMs.takeIf { it > 0 } ?: previous?.durationMs ?: 0L, now
            )
            watchHistory().filter { it.video.id != video.id && sameHistoryVideo(it.video, video) }
                .forEach { db.delete(WATCH_HISTORY_TABLE, "video_id = ?", arrayOf(it.video.id)) }
            val values = ContentValues().apply {
                put("video_id", video.id)
                put("record_json", watchRecordToJson(record).toString())
                put("updated_at", now)
            }
            db.insertWithOnConflict(WATCH_HISTORY_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            enqueueWatchSync(record)
            val overflow = mutableListOf<String>()
            db.rawQuery(
                "SELECT video_id FROM $WATCH_HISTORY_TABLE ORDER BY updated_at DESC LIMIT -1 OFFSET ?",
                arrayOf(MAX_WATCH_HISTORY.toString())
            ).use { cursor -> while (cursor.moveToNext()) overflow += cursor.getString(0) }
            overflow.forEach { db.delete(WATCH_HISTORY_TABLE, "video_id = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun enqueueWatchSync(record: WatchRecord) {
        val source = sources().firstOrNull { it.id == record.video.sourceId } ?: return
        val sourceKey = hubSourceKey(source.api)
        val videoKey = "$sourceKey:${syncEncode(record.video.remoteId)}"
        val episodeIndex = episodeIndex(record.episode.title)
        val progressKey = "$videoKey:${syncEncode(record.lineName)}:$episodeIndex"
        val payload = JSONObject().put("video", videoToJson(record.video)).put("source_api", source.api)
            .put("line_name", record.lineName).put("episode_title", record.episode.title)
            .put("line_index", 0).put("episode_index", episodeIndex)
            .put("position_ms", record.positionMs).put("duration_ms", record.durationMs)
            .put("updated_at", record.updatedAt)
        database.writeEpisodeProgress(progressKey, record.video.id, payload.toString(), record.updatedAt)
        database.enqueueSyncEnvelope("history", videoKey, record.updatedAt, false, payload.toString())
        database.enqueueSyncEnvelope("progress", progressKey, record.updatedAt, false, payload.toString())
    }

    private fun enqueueSourceSync(source: VideoSource) {
        val payload = JSONObject().put("name", source.name).put("api", source.api)
            .put("detail_url", source.detailUrl).put("enabled", source.enabled)
            .put("is_adult", source.isAdult).put("is_default", source.isDefault)
        database.enqueueSyncEnvelope("site", hubSourceKey(source.api), System.currentTimeMillis(), false, payload.toString())
    }

    fun enqueueLocalSyncSnapshot() {
        sources().forEach(::enqueueSourceSync)
        database.readableDatabase.query(FAVORITES_TABLE, arrayOf("video_json", "added_at"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { videoFromJson(JSONObject(cursor.getString(0))) }.getOrNull()?.let { video ->
                    val source = sources().firstOrNull { it.id == video.sourceId } ?: return@let
                    val updatedAt = cursor.getLong(1)
                    val key = "${hubSourceKey(source.api)}:${syncEncode(video.remoteId)}"
                    val payload = JSONObject().put("video", videoToJson(video)).put("source_api", source.api).put("added_at", updatedAt)
                    database.enqueueSyncEnvelope("favorite", key, updatedAt, false, payload.toString())
                }
            }
        }
        watchHistory().forEach(::enqueueWatchSync)
    }

    internal fun applyRemoteSyncEnvelopes(
        envelopes: List<SyncEnvelope>,
        localDeviceId: String = "local",
        isSnapshot: Boolean = false
    ) {
        val ordered = envelopes.sortedBy { if (it.entity == "site") 0 else 1 }
        ordered.forEach { envelope ->
            val payload = envelope.payload?.let(::JSONObject) ?: JSONObject()
            when (envelope.entity) {
                "site" -> applyRemoteSite(envelope, payload, localDeviceId)
                "favorite" -> applyRemoteFavorite(envelope, payload, localDeviceId, isSnapshot)
                "history", "progress" -> applyRemoteProgress(envelope, payload, localDeviceId, isSnapshot)
            }
        }
    }

    private fun applyRemoteSite(envelope: SyncEnvelope, payload: JSONObject, localDeviceId: String) {
        val queued = database.readSyncOutboxItem("site", envelope.key)
        if (queued != null && compareSyncState(queued.copy(deviceId = localDeviceId), envelope) > 0) return
        val existing = sources().firstOrNull { hubSourceKey(it.api) == envelope.key } ?: return if (envelope.deleted) Unit else run {
            val api = payload.optString("api")
            if (api.isBlank()) return
            val source = VideoSource("hub-${hubSourceKey(api).take(16)}", payload.optString("name", "同步站点"), api,
                payload.optString("detail_url"), payload.optBoolean("enabled", true), payload.optBoolean("is_adult"), payload.optBoolean("is_default"))
            saveSourceFromCloud(source)
        }
        if (envelope.deleted) deleteSourceFromCloud(existing)
        else saveSourceFromCloud(existing.copy(name = payload.optString("name", existing.name), api = payload.optString("api", existing.api),
            detailUrl = payload.optString("detail_url", existing.detailUrl), enabled = payload.optBoolean("enabled", existing.enabled),
            isAdult = payload.optBoolean("is_adult", existing.isAdult), isDefault = payload.optBoolean("is_default", existing.isDefault)))
    }

    private fun saveSourceFromCloud(source: VideoSource) {
        val all = sources().filterNot { it.id == source.id || (hubSourceKey(it.api) == hubSourceKey(source.api) && it.isDefault && !source.isDefault) }
        writeSources(if (source.isDefault) all.map { it.copy(isDefault = false) } + source else all + source)
    }

    private fun deleteSourceFromCloud(source: VideoSource) {
        writeSources(sources().filterNot { it.id == source.id })
    }

    private fun localVideo(payload: JSONObject): Pair<RemoteVideo, VideoSource>? {
        val raw = payload.optJSONObject("video") ?: payload
        val api = payload.optString("source_api")
        val source = sources().firstOrNull { api.isNotBlank() && hubSourceKey(it.api) == hubSourceKey(api) }
            ?: sources().firstOrNull { hubSourceKey(it.api) == envelopeSourceKey(payload, raw) }
            ?: return null
        val video = runCatching { videoFromJson(raw) }.getOrNull() ?: return null
        return video.copy(id = "${source.id}:${video.remoteId}", sourceId = source.id, sourceName = source.name) to source
    }

    private fun envelopeSourceKey(payload: JSONObject, video: JSONObject): String =
        payload.optString("source_key").ifBlank { video.optString("sourceKey") }

    private fun favoriteForSyncKey(key: String): RemoteVideo? = favorites().firstOrNull { video ->
        val source = sources().firstOrNull { it.id == video.sourceId } ?: return@firstOrNull false
        "${hubSourceKey(source.api)}:${syncEncode(video.remoteId)}" == key
    }

    private fun favoriteUpdatedAt(videoId: String): Long = database.readableDatabase.query(
        FAVORITES_TABLE, arrayOf("added_at"), "video_id = ?", arrayOf(videoId), null, null, null, "1"
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun enqueueFavoriteSync(video: RemoteVideo, updatedAt: Long) {
        val source = sources().firstOrNull { it.id == video.sourceId } ?: return
        val key = "${hubSourceKey(source.api)}:${syncEncode(video.remoteId)}"
        val payload = JSONObject().put("video", videoToJson(video)).put("source_api", source.api).put("added_at", updatedAt)
        database.enqueueSyncEnvelope("favorite", key, updatedAt, false, payload.toString())
    }

    private fun applyRemoteFavorite(envelope: SyncEnvelope, payload: JSONObject, localDeviceId: String, isSnapshot: Boolean) {
        val queued = database.readSyncOutboxItem("favorite", envelope.key)
        if (queued != null && compareSyncState(queued.copy(deviceId = localDeviceId), envelope) > 0) return
        val existingFavorite = favoriteForSyncKey(envelope.key)
        val local = existingFavorite?.let {
            SyncEnvelope("favorite", envelope.key, favoriteUpdatedAt(it.id), false, null, localDeviceId)
        }
        if (envelope.deleted) {
            if (local != null && compareSyncState(local, envelope) > 0) {
                if (!isSnapshot) enqueueFavoriteSync(existingFavorite!!, local.updatedAt)
                return
            }
            if (existingFavorite != null) database.writableDatabase.delete(FAVORITES_TABLE, "video_id = ?", arrayOf(existingFavorite.id))
            return
        }
        val (video, _) = localVideo(payload) ?: return
        if (local != null && compareSyncState(local, envelope) > 0) {
            if (!isSnapshot) enqueueFavoriteSync(existingFavorite!!, local.updatedAt)
            return
        }
        database.writableDatabase.insertWithOnConflict(FAVORITES_TABLE, null, ContentValues().apply {
            put("video_id", video.id); put("video_json", videoToJson(video).toString()); put("added_at", envelope.updatedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun historySyncKey(video: RemoteVideo): String? {
        val source = sources().firstOrNull { it.id == video.sourceId } ?: return null
        return "${hubSourceKey(source.api)}:${syncEncode(video.remoteId)}"
    }

    private fun progressSyncKey(record: WatchRecord): String? = historySyncKey(record.video)?.let { videoKey ->
        "$videoKey:${syncEncode(record.lineName)}:${episodeIndex(record.episode.title)}"
    }

    private fun applyRemoteProgress(
        envelope: SyncEnvelope,
        payload: JSONObject,
        localDeviceId: String,
        isSnapshot: Boolean
    ) {
        val queued = database.readSyncOutboxItem(envelope.entity, envelope.key)
        if (queued != null && compareSyncState(queued.copy(deviceId = localDeviceId), envelope) > 0) return

        if (envelope.entity == "progress") {
            val localEpisode = database.readEpisodeProgress(envelope.key)
            if (localEpisode != null && compareSyncState(localEpisode.copy(deviceId = localDeviceId), envelope) > 0) {
                if (!isSnapshot && !localEpisode.deleted) {
                    database.enqueueSyncEnvelope("progress", envelope.key, localEpisode.updatedAt, false,
                        JSONObject(localEpisode.payload ?: emptyMap<String, Any?>()).toString())
                }
                return
            }
            if (envelope.deleted) {
                database.writeEpisodeProgress(envelope.key, "", "{}", envelope.updatedAt, deleted = true)
                return
            }
        }

        if (envelope.deleted) {
            val localRecord = watchHistory().firstOrNull { historySyncKey(it.video) == envelope.key }
            if (localRecord != null && compareSyncState(
                    SyncEnvelope("history", envelope.key, localRecord.updatedAt, false, null, localDeviceId), envelope
                ) > 0
            ) {
                if (!isSnapshot) enqueueWatchSync(localRecord)
                return
            }
            if (localRecord != null) database.writableDatabase.delete(WATCH_HISTORY_TABLE, "video_id = ?", arrayOf(localRecord.video.id))
            return
        }

        val pair = localVideo(payload) ?: return
        val video = pair.first
        val record = WatchRecord(video, payload.optString("line_name"), PlaybackEpisode(payload.optString("episode_title"), ""),
            payload.optLong("position_ms"), payload.optLong("duration_ms"), envelope.updatedAt)
        if (envelope.entity == "progress") {
            database.writeEpisodeProgress(envelope.key, video.id, payload.toString(), envelope.updatedAt)
        }

        val current = watchRecord(video)
        val currentKey = current?.video?.let(::historySyncKey)
        if (envelope.entity == "history" && current != null && currentKey == envelope.key) {
            val local = SyncEnvelope("history", envelope.key, current.updatedAt, false, null, localDeviceId)
            if (compareSyncState(local, envelope) > 0) {
                if (!isSnapshot) enqueueWatchSync(current)
                return
            }
        }
        if (envelope.entity == "progress" && current != null && current.updatedAt >= envelope.updatedAt) return

        val db = database.writableDatabase
        db.insertWithOnConflict(WATCH_HISTORY_TABLE, null, ContentValues().apply {
            put("video_id", video.id); put("record_json", watchRecordToJson(record).toString()); put("updated_at", record.updatedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        val overflow = mutableListOf<String>()
        db.rawQuery("SELECT video_id FROM $WATCH_HISTORY_TABLE ORDER BY updated_at DESC LIMIT -1 OFFSET ?", arrayOf(MAX_WATCH_HISTORY.toString())).use { c ->
            while (c.moveToNext()) overflow += c.getString(0)
        }
        overflow.forEach { db.delete(WATCH_HISTORY_TABLE, "video_id = ?", arrayOf(it)) }
    }

    private fun episodeIndex(title: String): Int {
        val match = Regex("(?:第\\s*)?(\\d+)(?:\\s*[集话期])?").find(title)
        return match?.groupValues?.get(1)?.toIntOrNull()?.minus(1)?.coerceAtLeast(0) ?: 0
    }

    fun saveSource(source: VideoSource) {
        val current = sources().filterNot { it.id == source.id }
        val updated = if (source.isDefault) current.map { it.copy(isDefault = false) } + source
        else current + source
        writeSources(updated)
        enqueueSourceSync(source)
        if (source.isDefault) current.filter { it.isDefault }.forEach(::enqueueSourceSync)
    }

    fun setEnabled(source: VideoSource, enabled: Boolean) =
        saveSource(source.copy(enabled = enabled))

    fun setDefault(source: VideoSource) = saveSource(source.copy(isDefault = true, enabled = true))

    fun deleteSource(source: VideoSource) {
        writeSources(sources().filterNot { it.id == source.id })
        database.enqueueSyncEnvelope("site", hubSourceKey(source.api), System.currentTimeMillis(), deleted = true, payloadJson = null)
    }

    /** Imports the bundled source addresses from the Flutter app's default preset. */
    fun importDefaultSources(): Int {
        val bundled = listOf(
            VideoSource("dyttzy", "电影天堂资源", "http://caiji.dyttzyapi.com/api.php/provide/vod", "http://caiji.dyttzyapi.com"),
            VideoSource("heimuer", "黑木耳", "https://json.heimuer.xyz/api.php/provide/vod", "https://heimuer.tv"),
            VideoSource("ruyi", "如意资源", "https://cj.rycjapi.com/api.php/provide/vod"),
            VideoSource("bfzy", "暴风资源", "https://bfzyapi.com/api.php/provide/vod"),
            VideoSource("tyyszy", "天涯资源", "https://tyyszy.com/api.php/provide/vod"),
            VideoSource("ffzy", "非凡影视", "http://ffzy5.tv/api.php/provide/vod", "http://ffzy5.tv"),
            VideoSource("zy360", "360资源", "https://360zy.com/api.php/provide/vod"),
            VideoSource("maotaizy", "茅台资源", "https://caiji.maotaizy.cc/api.php/provide/vod"),
            VideoSource("wolong", "卧龙资源", "https://wolongzyw.com/api.php/provide/vod"),
            VideoSource("jisu", "极速资源", "https://jszyapi.com/api.php/provide/vod", "https://jszyapi.com"),
            VideoSource("dbzy", "豆瓣资源", "https://dbzy.tv/api.php/provide/vod"),
            VideoSource("mozhua", "魔爪资源", "https://mozhuazy.com/api.php/provide/vod"),
            VideoSource("mdzy", "魔都资源", "https://www.mdzyapi.com/api.php/provide/vod"),
            VideoSource("zuid", "最大资源", "https://api.zuidapi.com/api.php/provide/vod"),
            VideoSource("yinghua", "樱花资源", "https://m3u8.apiyhzy.com/api.php/provide/vod"),
            VideoSource("wujin", "无尽资源", "https://api.wujinapi.me/api.php/provide/vod"),
            VideoSource("wwzy", "旺旺短剧", "https://wwzy.tv/api.php/provide/vod"),
            VideoSource("ikun", "iKun资源", "https://ikunzyapi.com/api.php/provide/vod"),
            VideoSource("lzi", "量子资源站", "https://cj.lziapi.com/api.php/provide/vod"),
            VideoSource("xiaomaomi", "小猫咪资源", "https://zy.xmm.hk/api.php/provide/vod")
        )
        val selectedDefault = "ruyi"
        val bundledIds = bundled.mapTo(mutableSetOf()) { it.id }
        val otherSources = sources().filterNot { it.id in bundledIds }.map { it.copy(isDefault = false) }
        val imported = bundled.map { it.copy(isDefault = it.id == selectedDefault) }
        writeSources(otherSources + imported)
        return imported.size
    }

    fun importConfig(raw: String): ImportResult {
        val root = runCatching { JSONObject(raw) }.getOrElse {
            return ImportResult(0, 0, "JSON 格式无效")
        }
        val apiSites = root.optJSONObject("api_site")
            ?: return ImportResult(0, 0, "配置需要包含 api_site 对象")
        val configured = sources().associateBy { it.id }.toMutableMap()
        var imported = 0
        var skipped = 0
        val keys = apiSites.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val entry = apiSites.optJSONObject(key)
            val name = entry?.optString("name")?.trim().orEmpty()
            val api = entry?.optString("api")?.trim().orEmpty()
            val uri = runCatching { Uri.parse(api) }.getOrNull()
            if (entry == null || name.isEmpty() || !isHttpUrl(uri)) {
                skipped++
                continue
            }
            configured[key] = VideoSource(
                id = key,
                name = name,
                api = api,
                detailUrl = entry.optString("detail", "").trim(),
                enabled = true,
                isAdult = entry.optBoolean("is_adult", false),
                isDefault = configured[key]?.isDefault ?: false
            )
            imported++
        }
        if (imported == 0 && skipped == 0) return ImportResult(0, 0, "api_site 中没有可用站点")
        writeSources(configured.values.toList())
        return ImportResult(imported, skipped)
    }

    suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        val enabledSources = sources().filter { it.enabled }.sortedByDescending { it.isDefault }
        val results = coroutineScope {
            enabledSources.map { source -> async {
                try {
                    val payload = request(source, linkedMapOf(
                        "ac" to "videolist",
                        "wd" to query.trim(),
                        "pg" to "1",
                        "_" to System.currentTimeMillis().toString()
                    ))
                    SourceSearchReply(listFrom(payload).mapNotNull { item -> itemFrom(item, source) })
                } catch (error: Exception) {
                    SourceSearchReply(emptyList(), "${source.name}：${error.message ?: "请求失败"}")
                }
            } }.awaitAll()
        }
        SearchResult(
            videos = results.flatMap(SourceSearchReply::videos),
            errors = results.mapNotNull(SourceSearchReply::error)
        )
    }

    suspend fun searchSource(source: VideoSource, query: String): List<RemoteVideo> = withContext(Dispatchers.IO) {
        val payload = request(source, mapOf("ac" to "videolist", "wd" to query.trim(), "pg" to "1"))
        listFrom(payload).mapNotNull { itemFrom(it, source) }
    }

    suspend fun detail(video: RemoteVideo): RemoteVideoDetail = withContext(Dispatchers.IO) {
        val source = sources().firstOrNull { it.id == video.sourceId }
            ?: throw IllegalStateException("视频源已被删除")
        val payload = request(source, mapOf("ac" to "detail", "ids" to video.remoteId))
        val fullVideo = listFrom(payload).firstOrNull()?.let { itemFrom(it, source) } ?: video
        val raw = listFrom(payload).firstOrNull() ?: throw IllegalStateException("站点没有返回影片详情")
        RemoteVideoDetail(fullVideo, playbackLines(raw, source))
    }

    suspend fun categories(sourceId: String): List<VideoCategory> = withContext(Dispatchers.IO) {
        val source = sources().firstOrNull { it.id == sourceId }
            ?: throw IllegalStateException("视频源已被删除")
        val categories = categoryList(request(source, mapOf("ac" to "list")))
        if (categories.isNotEmpty()) return@withContext categories
        runCatching { categoryList(request(source, mapOf("ac" to "class"))) }
            .getOrDefault(emptyList())
    }

    suspend fun browse(
        sourceId: String,
        categoryId: String? = null,
        page: Int = 1
    ): VideoCatalogPage = withContext(Dispatchers.IO) {
        val source = sources().firstOrNull { it.id == sourceId && it.enabled }
            ?: throw IllegalStateException("默认视频源未启用")
        val requestedPage = page.coerceAtLeast(1)
        val parameters = linkedMapOf(
            "ac" to "videolist",
            "pg" to requestedPage.toString(),
            "_" to System.currentTimeMillis().toString()
        )
        if (!categoryId.isNullOrBlank()) parameters["t"] = categoryId
        val payload = request(source, parameters)
        val videos = listFrom(payload).mapNotNull { item -> itemFrom(item, source) }
        val pageCount = integerValue(payload, "pagecount")
        val currentPage = integerValue(payload, "page").takeIf { it > 0 } ?: requestedPage
        VideoCatalogPage(
            videos = videos,
            page = currentPage,
            hasMore = if (pageCount > 0) currentPage < pageCount else videos.size >= 20
        )
    }

    suspend fun testSource(source: VideoSource): Result<Long> = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            request(source, mapOf("ac" to "list", "pg" to "1"))
            Result.success((System.nanoTime() - started) / 1_000_000)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun request(source: VideoSource, parameters: Map<String, String>): JSONObject {
        val base = Uri.parse(source.api)
        val builder = base.buildUpon().clearQuery()
        base.queryParameterNames.forEach { key ->
            if (key !in parameters) base.getQueryParameters(key).forEach { value -> builder.appendQueryParameter(key, value) }
        }
        parameters.forEach { (key, value) -> builder.appendQueryParameter(key, value) }
        val connection = URL(builder.build().toString()).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("User-Agent", "CineoTV/1.0 Android")
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            runCatching { JSONObject(body) }.getOrElse { throw IllegalStateException("站点返回的不是有效 JSON") }
        } finally {
            connection.disconnect()
        }
    }

    private fun listFrom(payload: JSONObject): List<JSONObject> {
        val direct = payload.optJSONArray("list")
        val data = payload.opt("data")
        val array = direct ?: when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("list")
            else -> null
        } ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun categoryList(payload: JSONObject): List<VideoCategory> {
        val found = mutableListOf<JSONObject>()
        fun collect(value: Any?) {
            when (value) {
                is JSONArray -> (0 until value.length()).forEach { collect(value.opt(it)) }
                is JSONObject -> {
                    val hasId = value.has("type_id") || value.has("typeId") || value.has("id")
                    val hasName = value.has("type_name") || value.has("typeName") || value.has("name")
                    if (hasId && hasName) found += value
                    else value.keys().forEach { key -> collect(value.opt(key)) }
                }
            }
        }
        collect(payload.opt("class"))
        collect(payload.opt("types"))
        collect(payload.opt("categories"))
        collect(payload.opt("data"))
        val unique = linkedMapOf<String, VideoCategory>()
        found.forEach { raw ->
            val id = raw.optString("type_id").ifBlank { raw.optString("typeId") }.ifBlank { raw.optString("id") }.trim()
            val name = raw.optString("type_name").ifBlank { raw.optString("typeName") }.ifBlank { raw.optString("name") }.trim()
            val parent = raw.optString("type_pid").ifBlank { raw.optString("parent_id") }.ifBlank { raw.optString("parentId") }.trim()
            if (id.isNotEmpty() && name.isNotEmpty()) {
                unique.putIfAbsent(id, VideoCategory(id, name, parent.takeIf { it.isNotEmpty() && it != "0" }))
            }
        }
        return unique.values.toList()
    }

    private fun integerValue(payload: JSONObject, key: String): Int {
        val direct = payload.opt(key)
        val value = when (direct) {
            is Number -> direct.toInt()
            else -> direct?.toString()?.toIntOrNull()
        }
        if (value != null) return value
        val data = payload.optJSONObject("data") ?: return 0
        val nested = data.opt(key)
        return when (nested) {
            is Number -> nested.toInt()
            else -> nested?.toString()?.toIntOrNull() ?: 0
        }
    }

    private fun itemFrom(item: JSONObject, source: VideoSource): RemoteVideo? {
        val remoteId = item.optString("vod_id").trim()
        val title = item.optString("vod_name").trim()
        if (remoteId.isEmpty() || title.isEmpty()) return null
        val category = item.optString("type_name").ifBlank { item.optString("vod_class") }
        val poster = listOf("vod_pic", "vod_pic_thumb", "vod_pic_slide", "vod_img")
            .firstNotNullOfOrNull { item.optString(it).trim().takeIf(String::isNotEmpty) }
            ?.let { resolve(source.api, it) }.orEmpty()
        val description = item.optString("vod_content").replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return RemoteVideo(
            id = "${source.id}:$remoteId", sourceId = source.id, sourceName = source.name,
            remoteId = remoteId, title = title, category = category,
            year = item.optString("vod_year"), description = description,
            posterUrl = poster, rating = item.optString("vod_score")
        )
    }

    private fun playbackLines(item: JSONObject, source: VideoSource): List<PlaybackLine> {
        val names = item.optString("vod_play_from").split("\$\$\$")
        val groups = item.optString("vod_play_url").split("\$\$\$")
        return groups.mapIndexedNotNull { groupIndex, group ->
            val episodes = group.split('#').mapIndexedNotNull { episodeIndex, raw ->
                val entry = raw.trim()
                if (entry.isEmpty()) return@mapIndexedNotNull null
                val separator = entry.indexOf('$')
                val title = if (separator < 0) entry else entry.substring(0, separator).trim()
                val address = if (separator < 0) entry else entry.substring(separator + 1).trim()
                if (address.isEmpty()) null else PlaybackEpisode(
                    title.ifEmpty { "第 ${episodeIndex + 1} 集" },
                    resolve(source.api, address)
                )
            }
            if (episodes.isEmpty()) null else PlaybackLine(
                names.getOrNull(groupIndex)?.trim()?.takeIf(String::isNotEmpty) ?: "播放源 ${groupIndex + 1}",
                episodes
            )
        }
    }

    private fun resolve(base: String, value: String): String {
        val uri = Uri.parse(value)
        return if (uri.scheme != null) value else URL(URL(base), value).toString()
    }

    private fun isHttpUrl(uri: Uri?): Boolean = uri != null &&
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()

    private fun sourceFromJson(json: JSONObject) = VideoSource(
        id = json.getString("id"), name = json.getString("name"), api = json.getString("api"),
        detailUrl = json.optString("detailUrl"), enabled = json.optBoolean("enabled", true),
        isAdult = json.optBoolean("isAdult", false), isDefault = json.optBoolean("isDefault", false),
        lastLatencyMs = json.takeIf { it.has("lastLatencyMs") && !it.isNull("lastLatencyMs") }?.optLong("lastLatencyMs"),
        lastError = json.optString("lastError").takeIf(String::isNotBlank)
    )

    private fun sourceFromCursor(cursor: Cursor) = VideoSource(
        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
        api = cursor.getString(cursor.getColumnIndexOrThrow("api")),
        detailUrl = cursor.getString(cursor.getColumnIndexOrThrow("detail_url")),
        enabled = cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) != 0,
        isAdult = cursor.getInt(cursor.getColumnIndexOrThrow("is_adult")) != 0,
        isDefault = cursor.getInt(cursor.getColumnIndexOrThrow("is_default")) != 0,
        lastLatencyMs = cursor.getColumnIndexOrThrow("last_latency_ms").let { index ->
            if (cursor.isNull(index)) null else cursor.getLong(index)
        },
        lastError = cursor.getString(cursor.getColumnIndexOrThrow("last_error"))
    )

    private fun writeSources(sources: List<VideoSource>) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.delete(SOURCES_TABLE, null, null)
            sources.forEach { source ->
                db.insertOrThrow(SOURCES_TABLE, null, sourceValues(source))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun sourceValues(source: VideoSource) = ContentValues().apply {
        put("id", source.id)
        put("name", source.name)
        put("api", source.api)
        put("detail_url", source.detailUrl)
        put("enabled", if (source.enabled) 1 else 0)
        put("is_adult", if (source.isAdult) 1 else 0)
        put("is_default", if (source.isDefault) 1 else 0)
        put("last_latency_ms", source.lastLatencyMs)
        put("last_error", source.lastError)
    }

    private fun migrateLegacyVideoData() {
        val db = database.writableDatabase
        if (readSetting(db, VIDEO_DATA_MIGRATION_KEY) == "done") return

        db.beginTransaction()
        try {
            if (rowCount(db, SOURCES_TABLE) == 0L) {
                legacyPreferences.getString(SOURCE_KEY, null)?.let { raw ->
                    runCatching {
                        val array = JSONArray(raw)
                        for (index in 0 until array.length()) {
                            runCatching { sourceFromJson(array.getJSONObject(index)) }
                                .getOrNull()?.let { db.insertWithOnConflict(SOURCES_TABLE, null, sourceValues(it), SQLiteDatabase.CONFLICT_REPLACE) }
                        }
                    }
                }
            }
            if (rowCount(db, FAVORITES_TABLE) == 0L) {
                legacyPreferences.getString(LEGACY_FAVORITES_KEY, null)?.let { raw ->
                    runCatching {
                        val array = JSONArray(raw)
                        for (index in 0 until array.length()) {
                            runCatching { videoFromJson(array.getJSONObject(index)) }.getOrNull()?.let { video ->
                                val values = ContentValues().apply {
                                    put("video_id", video.id)
                                    put("video_json", videoToJson(video).toString())
                                    put("added_at", System.currentTimeMillis() - index)
                                }
                                db.insertWithOnConflict(FAVORITES_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                            }
                        }
                    }
                }
            }
            if (rowCount(db, WATCH_HISTORY_TABLE) == 0L) {
                legacyPreferences.getString(LEGACY_WATCH_HISTORY_KEY, null)?.let { raw ->
                    runCatching {
                        val array = JSONArray(raw)
                        for (index in 0 until array.length()) {
                            runCatching { watchRecordFromJson(array.getJSONObject(index)) }.getOrNull()?.let { record ->
                                val values = ContentValues().apply {
                                    put("video_id", record.video.id)
                                    put("record_json", watchRecordToJson(record).toString())
                                    put("updated_at", record.updatedAt)
                                }
                                db.insertWithOnConflict(WATCH_HISTORY_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                            }
                        }
                    }
                }
            }
            putSetting(db, VIDEO_DATA_MIGRATION_KEY, "done")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun readSetting(db: SQLiteDatabase, key: String): String? = db.query(
        APP_SETTINGS_TABLE,
        arrayOf("setting_value"),
        "setting_key = ?",
        arrayOf(key),
        null,
        null,
        null,
        "1"
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun putSetting(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict(
            APP_SETTINGS_TABLE,
            null,
            ContentValues().apply { put("setting_key", key); put("setting_value", value) },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun rowCount(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    private fun videoToJson(video: RemoteVideo) = JSONObject().apply {
        put("id", video.id)
        put("sourceId", video.sourceId)
        put("sourceName", video.sourceName)
        put("remoteId", video.remoteId)
        put("title", video.title)
        put("category", video.category)
        put("year", video.year)
        put("description", video.description)
        put("posterUrl", video.posterUrl)
        put("rating", video.rating)
    }

    private fun videoFromJson(json: JSONObject) = RemoteVideo(
        id = json.getString("id"),
        sourceId = json.getString("sourceId"),
        sourceName = json.optString("sourceName"),
        remoteId = json.getString("remoteId"),
        title = json.getString("title"),
        category = json.optString("category"),
        year = json.optString("year"),
        description = json.optString("description"),
        posterUrl = json.optString("posterUrl"),
        rating = json.optString("rating")
    )

    private fun watchRecordToJson(record: WatchRecord) = JSONObject().apply {
        put("video", videoToJson(record.video))
        put("lineName", record.lineName)
        put("episodeTitle", record.episode.title)
        put("episodeUrl", record.episode.url)
        put("positionMs", record.positionMs)
        put("durationMs", record.durationMs)
        put("updatedAt", record.updatedAt)
    }

    private fun watchRecordFromJson(json: JSONObject) = WatchRecord(
        video = videoFromJson(json.getJSONObject("video")),
        lineName = json.optString("lineName"),
        episode = PlaybackEpisode(json.optString("episodeTitle"), json.optString("episodeUrl")),
        positionMs = json.optLong("positionMs"),
        durationMs = json.optLong("durationMs"),
        updatedAt = json.optLong("updatedAt")
    )

    companion object {
        private const val SOURCES_TABLE = "video_sources"
        private const val FAVORITES_TABLE = "favorite_videos"
        private const val WATCH_HISTORY_TABLE = "watch_history"
        private const val APP_SETTINGS_TABLE = "app_settings"
        private const val SOURCE_KEY = "sources_json"
        private const val LEGACY_FAVORITES_KEY = "favorite_videos_json"
        private const val LEGACY_WATCH_HISTORY_KEY = "watch_history_json"
        private const val VIDEO_DATA_MIGRATION_KEY = "migration.video_data.v1"
        private const val MAX_WATCH_HISTORY = 50
    }
}
