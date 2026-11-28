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
        createQuarantineTable(db)
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
        if (oldVersion < 5) {
            createQuarantineTable(db)
            quarantineOutbox(db)
            db.delete("sync_state", null, null)
        }
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

    private fun createQuarantineTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE sync_quarantine (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            entity TEXT NOT NULL, sync_key TEXT NOT NULL, updated_at INTEGER NOT NULL,
            deleted INTEGER NOT NULL, payload_json TEXT, generation INTEGER, baseline_at INTEGER,
            previous_binding TEXT NOT NULL DEFAULT ''
        )""".trimIndent())
    }

    // All rows are copied before removal, in the same SQLite transaction. The
    // archive deliberately has no entity/key uniqueness constraint.
    private fun quarantineOutbox(db: SQLiteDatabase) {
        db.execSQL("""INSERT INTO sync_quarantine(entity,sync_key,updated_at,deleted,payload_json,generation,baseline_at,previous_binding)
            SELECT entity,sync_key,updated_at,deleted,payload_json,generation,baseline_at,
                COALESCE((SELECT state_value FROM sync_state WHERE state_key='binding_instance'),'') FROM sync_outbox""".trimIndent())
        db.delete("sync_outbox", null, null)
    }

    /** Invalidate the old SQLite binding before changing the credential store. */
    internal fun beginBindingTransition() = synchronized(TmdbAccessGate.lock) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            quarantineOutbox(db)
            db.delete("sync_state", null, null)
            db.insertOrThrow("sync_state", null, ContentValues().apply {
                put("state_key", "hub_settings_epoch"); put("state_value", java.util.UUID.randomUUID().toString())
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Finish only after credentials were durably saved; writes during the gap are quarantined. */
    internal fun activateBinding(instance: String, deviceId: String) = synchronized(TmdbAccessGate.lock) {
        require(instance.isNotBlank() && instance != "legacy")
        val db = writableDatabase
        db.beginTransaction()
        try {
            quarantineOutbox(db)
            db.delete("sync_state", null, null)
            mapOf("binding_instance" to instance, "bound_at" to System.currentTimeMillis().toString(),
                "device_id" to deviceId, "generation" to "0", "baseline_at" to "0", "cursor" to "0",
                "hub_settings_epoch" to java.util.UUID.randomUUID().toString()).forEach { (key, value) ->
                db.insertOrThrow("sync_state", null, ContentValues().apply { put("state_key", key); put("state_value", value) })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    internal fun bindingMatches(instance: String): Boolean = instance.isNotBlank() && instance != "legacy" &&
        readSyncState("binding_instance") == instance && readSyncState("bound_at").isNotBlank() && readSyncState("device_revoked") != "1"

    internal fun syncOutboxTotal(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_outbox", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    internal fun quarantinedOutboxCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_quarantine", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    internal fun quarantinedOutboxSummary(): List<String> = readableDatabase.query(
        "sync_quarantine", arrayOf("entity", "sync_key", "deleted"), null, null, null, null, "id DESC", "20"
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add("${cursor.getString(0)} · ${cursor.getString(1)}${if (cursor.getInt(2) != 0) "（删除）" else ""}")
    } }

    internal fun quarantineStaleOutbox(generation: Long, baselineAt: Long, binding: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(bindingMatches(binding)) { "绑定已变更" }
            val args = arrayOf<Any>(binding, generation, baselineAt)
            db.execSQL("""INSERT INTO sync_quarantine(entity,sync_key,updated_at,deleted,payload_json,generation,baseline_at,previous_binding)
                SELECT entity,sync_key,updated_at,deleted,payload_json,generation,baseline_at,? FROM sync_outbox
                WHERE generation IS NULL OR generation != ? OR baseline_at IS NULL OR baseline_at < ?""".trimIndent(), args)
            db.delete("sync_outbox", "generation IS NULL OR generation != ? OR baseline_at IS NULL OR baseline_at < ?",
                arrayOf(generation.toString(), baselineAt.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun untrustedWatchKeys(db: SQLiteDatabase, generation: Long? = null, baseline: Long? = null): List<Pair<String, String>> = db.query(
        "sync_outbox", arrayOf("entity", "sync_key", "updated_at", "deleted", "payload_json"),
        if (generation == null || baseline == null) "entity IN ('history','progress')" else "entity IN ('history','progress') AND generation=? AND baseline_at>=?",
        if (generation == null || baseline == null) null else arrayOf(generation.toString(), baseline.toString()), null, null, null
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) {
            val entity = cursor.getString(0)
            val key = cursor.getString(1)
            val payload = runCatching { cursor.getString(4)?.let { org.json.JSONObject(it).toMap() } }.getOrNull()
            val item = SyncEnvelope(entity, key, cursor.getLong(2), cursor.getInt(3) != 0, payload)
            if (!hasTrustedWatchIdentity(item)) add(entity to key)
        }
    } }

    internal fun untrustedWatchCount(generation: Long, baseline: Long): Int = untrustedWatchKeys(readableDatabase, generation, baseline).size

    internal fun quarantineUntrustedWatch(binding: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(bindingMatches(binding)) { "绑定已变更" }
            for ((entity, key) in untrustedWatchKeys(db)) {
                db.execSQL("""INSERT INTO sync_quarantine(entity,sync_key,updated_at,deleted,payload_json,generation,baseline_at,previous_binding)
                    SELECT entity,sync_key,updated_at,deleted,payload_json,generation,baseline_at,? FROM sync_outbox
                    WHERE entity=? AND sync_key=?""".trimIndent(), arrayOf(binding, entity, key))
                db.delete("sync_outbox", "entity=? AND sync_key=?", arrayOf(entity, key))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    internal fun quarantinePage(beforeId: Long?, limit: Int = 10): List<QuarantinedSyncRecord> = readableDatabase.query(
        "sync_quarantine", arrayOf("id", "entity", "sync_key", "updated_at", "deleted", "payload_json", "generation", "baseline_at", "previous_binding"),
        if (beforeId == null) null else "id < ?", beforeId?.let { arrayOf(it.toString()) }, null, null, "id DESC", limit.coerceIn(1, 50).toString()
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(QuarantinedSyncRecord(cursor.getLong(0), cursor.getString(1), cursor.getString(2),
            cursor.getLong(3), cursor.getInt(4) != 0, cursor.getString(5), if (cursor.isNull(6)) null else cursor.getLong(6),
            if (cursor.isNull(7)) null else cursor.getLong(7), cursor.getString(8)))
    } }

    /** Insert without replacing any active or stale same-key change. */
    internal fun restoreQuarantined(record: QuarantinedSyncRecord, binding: String): String? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (!bindingMatches(binding)) return "绑定已变更，请重新核对"
            val actual = db.query("sync_quarantine", arrayOf("id", "entity", "sync_key", "updated_at", "deleted", "payload_json", "generation", "baseline_at", "previous_binding"),
                "id = ?", arrayOf(record.id.toString()), null, null, null).use { c ->
                if (!c.moveToFirst()) null else QuarantinedSyncRecord(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4) != 0,
                    c.getString(5), if (c.isNull(6)) null else c.getLong(6), if (c.isNull(7)) null else c.getLong(7), c.getString(8))
            }
            if (actual != record) return "记录已变化，请重新核对"
            quarantineRestoreError(record)?.let { return it }
            val exists = db.rawQuery("SELECT 1 FROM sync_outbox WHERE entity = ? AND sync_key = ? LIMIT 1", arrayOf(record.entity, record.key)).use { it.moveToFirst() }
            if (exists) return "同一项目已有待同步更改；请先处理该更改"
            val generation = readSyncState("generation", "0").toLongOrNull() ?: return "同步状态无效"
            val baseline = readSyncState("baseline_at", "0").toLongOrNull() ?: return "同步状态无效"
            val values = ContentValues().apply {
                put("entity", record.entity); put("sync_key", record.key); put("updated_at", record.updatedAt)
                put("deleted", 0); put("payload_json", record.payloadJson); put("generation", generation); put("baseline_at", baseline)
            }
            if (db.insertWithOnConflict("sync_outbox", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) return "同一项目已有待同步更改"
            db.setTransactionSuccessful()
            return null
        } finally { db.endTransaction() }
    }

    fun enqueueSyncEnvelope(entity: String, key: String, updatedAt: Long, deleted: Boolean, payloadJson: String?, generation: Long? = null, baselineAt: Long? = null) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val active = readSyncState("binding_instance").isNotBlank() && readSyncState("bound_at").isNotBlank()
            val activeGeneration = generation ?: if (active) readSyncState("generation", "0").toLongOrNull() else null
            val activeBaseline = baselineAt ?: if (active) readSyncState("baseline_at", "0").toLongOrNull() else null
            val values = ContentValues().apply {
                put("entity", entity); put("sync_key", key); put("updated_at", updatedAt)
                put("deleted", if (deleted) 1 else 0); put("payload_json", payloadJson)
                if (activeGeneration == null) putNull("generation") else put("generation", activeGeneration)
                if (activeBaseline == null) putNull("baseline_at") else put("baseline_at", activeBaseline)
            }
            if (active) {
                // A stale generation may have the same entity/key as a new edit.
                // Preserve that older change before the current row replaces it.
                db.execSQL("""INSERT INTO sync_quarantine(entity,sync_key,updated_at,deleted,payload_json,generation,baseline_at,previous_binding)
                    SELECT entity,sync_key,updated_at,deleted,payload_json,generation,baseline_at,? FROM sync_outbox
                    WHERE entity=? AND sync_key=? AND (generation IS NULL OR generation != ? OR baseline_at IS NULL OR baseline_at < ?)""".trimIndent(),
                    arrayOf<Any>(readSyncState("binding_instance"), entity, key, activeGeneration ?: -1L, activeBaseline ?: -1L))
                db.insertWithOnConflict("sync_outbox", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            } else db.insertOrThrow("sync_quarantine", null, values)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    internal fun readSyncOutbox(limit: Int = 500, generation: Long? = null, baselineAt: Long? = null): List<SyncEnvelope> = readableDatabase.query(
        "sync_outbox", arrayOf("entity", "sync_key", "updated_at", "deleted", "payload_json", "generation", "baseline_at"),
        if (generation != null && baselineAt != null) "generation = ? AND baseline_at >= ?" else null,
        if (generation != null && baselineAt != null) arrayOf(generation.toString(), baselineAt.toString()) else null,
        null, null, "updated_at ASC", limit.coerceAtLeast(1).toString()
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
                val current = readSyncOutboxItem(item.entity, item.key)
                if (current == item) {
                    db.delete("sync_outbox", "entity = ? AND sync_key = ? AND updated_at = ?",
                        arrayOf(item.entity, item.key, item.updatedAt.toString()))
                }
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
        clearWatchConfirmations()
        writeSyncState(mapOf("bound_at" to "", "cursor" to "0", "generation" to "0", "baseline_at" to "0"))
    }

    fun clearWatchConfirmations() {
        writableDatabase.delete("sync_state", "state_key LIKE ? OR state_key LIKE ?", arrayOf("watch_confirmed:%", "watch_snapshot_hold:%"))
    }

    internal fun invalidateHubSettings(revoked: Boolean = false) = synchronized(TmdbAccessGate.lock) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val epoch = java.util.UUID.randomUUID().toString()
            val state = if (revoked) mapOf("hub_settings_ready" to "", "device_revoked" to "1", "hub_settings_epoch" to epoch)
                else mapOf("hub_settings_ready" to "", "hub_settings_epoch" to epoch)
            state.forEach { (key, value) ->
                check(db.insertWithOnConflict("sync_state", null, ContentValues().apply {
                    put("state_key", key); put("state_value", value)
                }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "无法保存云端设置状态" }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    internal fun applyHubSettings(binding: String, values: Map<String, String>) = synchronized(TmdbAccessGate.lock) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(bindingMatches(binding)) { "绑定已变更" }
            values.forEach { (key, value) ->
                require(key.startsWith("hub."))
                check(db.insertWithOnConflict(APP_SETTINGS_TABLE, null, ContentValues().apply {
                    put("setting_key", key); put("setting_value", value)
                }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "无法保存云端设置" }
            }
            mapOf("hub_settings_ready" to binding, "hub_settings_epoch" to java.util.UUID.randomUUID().toString()).forEach { (key, value) ->
                check(db.insertWithOnConflict("sync_state", null, ContentValues().apply {
                    put("state_key", key); put("state_value", value)
                }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "无法保存云端设置状态" }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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

    internal fun deleteEpisodeProgress(key: String, updatedAt: Long): Int =
        writableDatabase.delete("episode_progress", "progress_key = ? AND updated_at <= ?", arrayOf(key, updatedAt.toString()))

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
        private const val DATABASE_VERSION = 5
        private const val APP_SETTINGS_TABLE = "app_settings"
        private const val TMDB_METADATA_CACHE_TABLE = "tmdb_metadata_cache"
    }
}
