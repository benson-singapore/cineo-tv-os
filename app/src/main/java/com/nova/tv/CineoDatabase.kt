package com.nova.tv

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Local SQLite store for app configuration and user video activity. */
class CineoDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE video_sources (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                api TEXT NOT NULL,
                detail_url TEXT NOT NULL DEFAULT '',
                enabled INTEGER NOT NULL DEFAULT 1,
                is_adult INTEGER NOT NULL DEFAULT 0,
                is_default INTEGER NOT NULL DEFAULT 0,
                last_latency_ms INTEGER,
                last_error TEXT
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX video_sources_enabled_default ON video_sources(enabled, is_default)")
        db.execSQL(
            """CREATE TABLE favorite_videos (
                video_id TEXT PRIMARY KEY NOT NULL,
                video_json TEXT NOT NULL,
                added_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX favorite_videos_added_at ON favorite_videos(added_at DESC)")
        db.execSQL(
            """CREATE TABLE watch_history (
                video_id TEXT PRIMARY KEY NOT NULL,
                record_json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX watch_history_updated_at ON watch_history(updated_at DESC)")
        createTmdbMetadataCacheTable(db)
        createSyncTables(db)
        createEpisodeProgressTable(db)
        db.execSQL(
            """CREATE TABLE app_settings (
                setting_key TEXT PRIMARY KEY NOT NULL,
                setting_value TEXT NOT NULL
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createTmdbMetadataCacheTable(db)
        if (oldVersion < 3) createSyncTables(db)
        if (oldVersion < 4) createEpisodeProgressTable(db)
    }

    private fun createSyncTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE sync_outbox (
            entity TEXT NOT NULL,
            sync_key TEXT NOT NULL,
            updated_at INTEGER NOT NULL,
            deleted INTEGER NOT NULL,
            payload_json TEXT,
            generation INTEGER,
            baseline_at INTEGER,
            PRIMARY KEY(entity, sync_key)
        )""".trimIndent())
        db.execSQL("CREATE INDEX sync_outbox_updated_at ON sync_outbox(updated_at)")
        db.execSQL("""CREATE TABLE sync_state (
            state_key TEXT PRIMARY KEY NOT NULL,
            state_value TEXT NOT NULL
        )""".trimIndent())
    }

    fun enqueueSyncEnvelope(entity: String, key: String, updatedAt: Long, deleted: Boolean, payloadJson: String?, generation: Long? = null, baselineAt: Long? = null) {
        val hasBinding = readSyncState("bound_at").isNotBlank()
        val activeGeneration = generation ?: if (hasBinding) readSyncState("generation", "0").toLongOrNull() else null
        val activeBaseline = baselineAt ?: if (hasBinding) readSyncState("baseline_at", "0").toLongOrNull() else null
        writableDatabase.insertWithOnConflict("sync_outbox", null, ContentValues().apply {
            put("entity", entity); put("sync_key", key); put("updated_at", updatedAt)
            put("deleted", if (deleted) 1 else 0); put("payload_json", payloadJson)
            if (activeGeneration == null) putNull("generation") else put("generation", activeGeneration)
            if (activeBaseline == null) putNull("baseline_at") else put("baseline_at", activeBaseline)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    internal fun readSyncOutbox(limit: Int = 500, generation: Long? = null, baselineAt: Long? = null): List<SyncEnvelope> = readableDatabase.query(
        "sync_outbox", arrayOf("entity", "sync_key", "updated_at", "deleted", "payload_json", "generation", "baseline_at"),
        if (generation != null && baselineAt != null) "generation = ? AND baseline_at >= ?" else null,
        if (generation != null && baselineAt != null) arrayOf(generation.toString(), baselineAt.toString()) else null,
        null, null, "updated_at ASC", limit.coerceIn(1, 500).toString()
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) {
            val raw = cursor.getString(4)
            val payload = raw?.let { org.json.JSONObject(it).toMap() }
            add(SyncEnvelope(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getInt(3) != 0, payload,
                generation = cursor.getColumnIndex("generation").let { if (cursor.isNull(it)) null else cursor.getLong(it) },
                baselineAt = cursor.getColumnIndex("baseline_at").let { if (cursor.isNull(it)) null else cursor.getLong(it) }))
        }
    } }

    internal fun syncOutboxCount(generation: Long, baselineAt: Long): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM sync_outbox WHERE entity != 'setting' AND generation = ? AND baseline_at >= ?",
        arrayOf(generation.toString(), baselineAt.toString())
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    internal fun readLatestOutbox(entity: String, generation: Long, baselineAt: Long): SyncEnvelope? = readableDatabase.query(
        "sync_outbox", arrayOf("sync_key", "updated_at", "deleted", "payload_json", "generation", "baseline_at"),
        "entity = ? AND generation = ? AND baseline_at >= ?", arrayOf(entity, generation.toString(), baselineAt.toString()),
        null, null, "updated_at DESC", "1"
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else SyncEnvelope(entity, cursor.getString(0), cursor.getLong(1), cursor.getInt(2) != 0,
            cursor.getString(3)?.let { org.json.JSONObject(it).toMap() }, generation = cursor.getLong(4), baselineAt = cursor.getLong(5))
    }

    internal fun readLatestProgressOutbox(generation: Long, baselineAt: Long): SyncEnvelope? = readableDatabase.query(
        "sync_outbox", arrayOf("entity", "sync_key", "updated_at", "deleted", "payload_json", "generation", "baseline_at"),
        "entity = 'progress' AND generation = ? AND baseline_at >= ?", arrayOf(generation.toString(), baselineAt.toString()),
        null, null, "updated_at DESC", "1"
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else SyncEnvelope("progress", cursor.getString(1), cursor.getLong(2), cursor.getInt(3) != 0,
            cursor.getString(4)?.let { org.json.JSONObject(it).toMap() }, generation = cursor.getLong(5), baselineAt = cursor.getLong(6))
    }

    internal fun readSyncOutboxItem(entity: String, key: String): SyncEnvelope? = readableDatabase.query(
        "sync_outbox", arrayOf("updated_at", "deleted", "payload_json", "generation", "baseline_at"),
        "entity = ? AND sync_key = ?", arrayOf(entity, key), null, null, null, "1"
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else SyncEnvelope(
            entity = entity,
            key = key,
            updatedAt = cursor.getLong(0),
            deleted = cursor.getInt(1) != 0,
            payload = decodeOutboxPayload(cursor.getString(2)),
            generation = if (cursor.isNull(3)) null else cursor.getLong(3),
            baselineAt = if (cursor.isNull(4)) null else cursor.getLong(4)
        )
    }

    internal fun removeSyncOutbox(envelopes: List<SyncEnvelope>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            envelopes.forEach { item ->
                db.delete("sync_outbox", "entity = ? AND sync_key = ? AND updated_at = ?",
                    arrayOf(item.entity, item.key, item.updatedAt.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun clearSyncOutbox() { writableDatabase.delete("sync_outbox", null, null) }

    fun staleSyncOutboxCount(generation: Long, baselineAt: Long): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM sync_outbox WHERE generation IS NULL OR generation != ? OR baseline_at IS NULL OR baseline_at < ?",
        arrayOf(generation.toString(), baselineAt.toString())
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun discardStaleSyncOutbox(generation: Long, baselineAt: Long): Int = writableDatabase.delete(
        "sync_outbox", "generation IS NULL OR generation != ? OR baseline_at IS NULL OR baseline_at < ?",
        arrayOf(generation.toString(), baselineAt.toString())
    )

    fun clearSyncBindingState() {
        writeSyncState(mapOf("bound_at" to "", "cursor" to "0", "generation" to "0", "baseline_at" to "0"))
    }

    fun clearHubManagedSettings() {
        writableDatabase.delete(APP_SETTINGS_TABLE, "setting_key LIKE 'hub.%'", null)
    }

    fun readSyncState(key: String, default: String = ""): String = readableDatabase.query(
        "sync_state", arrayOf("state_value"), "state_key = ?", arrayOf(key), null, null, null, "1"
    ).use { if (it.moveToFirst()) it.getString(0) else default }

    fun writeSyncState(values: Map<String, String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            values.forEach { (key, value) -> db.insertWithOnConflict("sync_state", null, ContentValues().apply {
                put("state_key", key); put("state_value", value)
            }, SQLiteDatabase.CONFLICT_REPLACE) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun createEpisodeProgressTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE episode_progress (
            progress_key TEXT PRIMARY KEY NOT NULL,
            video_id TEXT NOT NULL,
            record_json TEXT NOT NULL,
            updated_at INTEGER NOT NULL,
            deleted INTEGER NOT NULL DEFAULT 0
        )""".trimIndent())
        db.execSQL("CREATE INDEX episode_progress_video_id ON episode_progress(video_id, updated_at DESC)")
    }

    fun writeEpisodeProgress(key: String, videoId: String, recordJson: String, updatedAt: Long, deleted: Boolean = false) {
        writableDatabase.insertWithOnConflict("episode_progress", null, ContentValues().apply {
            put("progress_key", key); put("video_id", videoId); put("record_json", recordJson)
            put("updated_at", updatedAt); put("deleted", if (deleted) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    internal fun readEpisodeProgress(key: String): SyncEnvelope? = readableDatabase.query(
        "episode_progress", arrayOf("record_json", "updated_at", "deleted"), "progress_key = ?", arrayOf(key), null, null, null, "1"
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else {
            val payload = org.json.JSONObject(cursor.getString(0)).toMap()
            SyncEnvelope("progress", key, cursor.getLong(1), cursor.getInt(2) != 0, payload)
        }
    }

    private fun createTmdbMetadataCacheTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tmdb_metadata_cache (
                cache_key TEXT PRIMARY KEY NOT NULL,
                metadata_json TEXT NOT NULL,
                last_accessed_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX tmdb_metadata_cache_last_accessed ON tmdb_metadata_cache(last_accessed_at)")
    }

    fun readTmdbMetadataCache(cacheKey: String): Pair<String, Long>? = readableDatabase.query(
        TMDB_METADATA_CACHE_TABLE,
        arrayOf("metadata_json", "last_accessed_at"),
        "cache_key = ?",
        arrayOf(cacheKey),
        null,
        null,
        null,
        "1"
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null }

    fun writeTmdbMetadataCache(cacheKey: String, metadataJson: String, lastAccessedAt: Long) {
        writableDatabase.insertWithOnConflict(
            TMDB_METADATA_CACHE_TABLE,
            null,
            ContentValues().apply {
                put("cache_key", cacheKey)
                put("metadata_json", metadataJson)
                put("last_accessed_at", lastAccessedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun touchTmdbMetadataCache(cacheKey: String, lastAccessedAt: Long) {
        writableDatabase.update(
            TMDB_METADATA_CACHE_TABLE,
            ContentValues().apply { put("last_accessed_at", lastAccessedAt) },
            "cache_key = ?",
            arrayOf(cacheKey)
        )
    }

    fun deleteTmdbMetadataCache(cacheKey: String) {
        writableDatabase.delete(TMDB_METADATA_CACHE_TABLE, "cache_key = ?", arrayOf(cacheKey))
    }

    fun deleteExpiredTmdbMetadataCache(olderThan: Long) {
        writableDatabase.delete(TMDB_METADATA_CACHE_TABLE, "last_accessed_at < ?", arrayOf(olderThan.toString()))
    }

    fun clearTmdbMetadataCache() {
        writableDatabase.delete(TMDB_METADATA_CACHE_TABLE, null, null)
    }

    fun readAppSetting(key: String): String? = readableDatabase.query(
        APP_SETTINGS_TABLE,
        arrayOf("setting_value"),
        "setting_key = ?",
        arrayOf(key),
        null,
        null,
        null,
        "1"
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun writeAppSettings(values: Map<String, String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            values.forEach { (key, value) ->
                db.insertWithOnConflict(
                    APP_SETTINGS_TABLE,
                    null,
                    ContentValues().apply { put("setting_key", key); put("setting_value", value) },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private const val DATABASE_NAME = "cineo_tv.db"
        private const val DATABASE_VERSION = 4
        private const val APP_SETTINGS_TABLE = "app_settings"
        private const val TMDB_METADATA_CACHE_TABLE = "tmdb_metadata_cache"
    }
}
