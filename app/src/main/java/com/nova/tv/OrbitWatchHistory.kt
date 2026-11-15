package com.nova.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class OrbitWatchSelection(
    val configId: String,
    val channelId: String,
    val video: OrbitVideoItem,
    val episode: OrbitEpisodeItem,
)

internal data class OrbitWatchEntry(
    val selection: OrbitWatchSelection,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

/** Local only; kept separate from the normal library and Hub sync database. */
internal class OrbitWatchHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("orbit_watch_history", Context.MODE_PRIVATE)
    private val lock = Any()

    fun entries(configId: String): List<OrbitWatchEntry> = synchronized(lock) {
        val array = runCatching { JSONArray(prefs.getString(configId, "[]")) }.getOrElse { JSONArray() }
        (0 until array.length()).mapNotNull { index ->
            runCatching {
                val obj = array.getJSONObject(index)
                OrbitWatchEntry(
                    OrbitWatchSelection(configId, obj.getString("channel"),
                        OrbitVideoItem(obj.getString("videoId"), obj.getString("title"), obj.optString("summary"), obj.optString("image"), JSONObject()),
                        OrbitEpisodeItem(obj.getString("episodeId"), obj.getString("episodeTitle"), JSONObject())),
                    obj.getLong("position"), obj.getLong("duration"), obj.getLong("updatedAt"))
            }.getOrNull()
        }.sortedByDescending { it.updatedAt }
    }

    fun save(selection: OrbitWatchSelection, position: Long, duration: Long) = synchronized(lock) {
        val existing = entries(selection.configId)
        val previous = existing.firstOrNull { it.selection.channelId == selection.channelId && it.selection.video.id == selection.video.id && it.selection.episode.id == selection.episode.id }
        val prior = existing.filterNot {
            it.selection.channelId == selection.channelId && it.selection.video.id == selection.video.id
        }
        val item = JSONObject().put("channel", selection.channelId).put("videoId", selection.video.id)
            .put("title", selection.video.title).put("summary", selection.video.summary).put("image", selection.video.imageUrl)
            .put("episodeId", selection.episode.id).put("episodeTitle", selection.episode.title)
            .put("position", position.coerceAtLeast(0)).put("duration", duration.takeIf { it > 0 } ?: previous?.durationMs ?: 0L)
            .put("updatedAt", System.currentTimeMillis())
        val array = JSONArray().put(item)
        prior.take(199).forEach { entry ->
            val s = entry.selection
            array.put(JSONObject().put("channel", s.channelId).put("videoId", s.video.id)
                .put("title", s.video.title).put("summary", s.video.summary).put("image", s.video.imageUrl)
                .put("episodeId", s.episode.id).put("episodeTitle", s.episode.title)
                .put("position", entry.positionMs).put("duration", entry.durationMs).put("updatedAt", entry.updatedAt))
        }
        prefs.edit().putString(selection.configId, array.toString()).apply()
    }
}
