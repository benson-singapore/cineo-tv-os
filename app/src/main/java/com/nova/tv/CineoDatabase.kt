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
        db.execSQL(
            """CREATE TABLE app_settings (
                setting_key TEXT PRIMARY KEY NOT NULL,
                setting_value TEXT NOT NULL
            )""".trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) {
            onCreate(db)
        } else if (oldVersion < 2) {
            createTmdbMetadataCacheTable(db)
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
        private const val DATABASE_VERSION = 2
        private const val APP_SETTINGS_TABLE = "app_settings"
        private const val TMDB_METADATA_CACHE_TABLE = "tmdb_metadata_cache"
    }
}
