package com.nova.tv

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** Uses unique test-only database and preferences on an isolated emulator. */
private class IsolatedDatabaseContext(base: Context, private val prefix: String) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
    private fun safeName(name: String) = "$prefix-$name"
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        baseContext.openOrCreateDatabase(safeName(name), mode, factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
        baseContext.openOrCreateDatabase(safeName(name), mode, factory, errorHandler)
    override fun getDatabasePath(name: String) = java.io.File(baseContext.cacheDir, safeName(name))
    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(safeName(name), mode)
}

class WatchSnapshotInstrumentedTest {
    private lateinit var context: IsolatedDatabaseContext
    private lateinit var db: CineoDatabase
    private lateinit var repo: MacCmsRepository
    private lateinit var engine: HubSyncEngine
    private val source = VideoSource("qa-source", "QA", "https://isolation.example.test/api")
    private val episode = PlaybackEpisode("第 1 集", "https://isolation.example.test/1", 0)
    private fun video(id: String) = RemoteVideo("qa-source:$id", source.id, source.name, id, "视频 $id", "剧集", "2026", "", "", "")
    private fun key(id: String) = "${hubSourceKey(source.api)}:$id"

    @Before fun setup() {
        val instrumentationContext = InstrumentationRegistry.getInstrumentation().targetContext
        context = IsolatedDatabaseContext(instrumentationContext, "snapshot-qa-${UUID.randomUUID()}")
        check(context.getDatabasePath("cineo_tv.db").parentFile!!.let { it.isDirectory || it.mkdirs() })
        db = CineoDatabase(context)
        repo = MacCmsRepository(context)
        engine = HubSyncEngine(context, repo, TmdbSettingsStore(context))
        repo.saveSource(source)
        db.clearSyncOutbox()
    }

    @After fun cleanup() {
        if (::db.isInitialized) db.close()
        if (::context.isInitialized) context.deleteDatabase("cineo_tv.db")
    }

    private fun save(id: String): SyncEnvelope {
        val video = video(id)
        repo.saveWatchProgress(video, "A", episode, 12_000, 60_000)
        return requireNotNull(db.readSyncOutboxItem("progress", "${key(id)}:A:0"))
    }
    private fun count(table: String, where: String = "1", args: Array<String> = emptyArray()): Int =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", args).use { it.moveToFirst(); it.getInt(0) }
    private fun page(items: List<SyncEnvelope> = emptyList(), next: Long = 10, more: Boolean = false): HubHttpReply {
        val data = JSONObject().put("generation", 1).put("snapshot_seq", 10).put("next_seq", next)
            .put("has_more", more).put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        return HubHttpReply(200, HubEnvelopeResult(true, data, null), null)
    }
    private fun invalidated() = HubHttpReply(409, HubEnvelopeResult(false, null, HubFailure.ResyncRequired), null)

    @Test fun sqliteReconcileDeletesConfirmedButPreservesPendingLegacyAndOrbit() = runBlocking {
        val confirmed = save("confirmed")
        repo.recordConfirmedWatch(listOf(confirmed))
        db.clearSyncOutbox()
        val pending = save("pending")
        save("legacy")
        db.removeSyncOutbox(listOf(requireNotNull(db.readSyncOutboxItem("progress", "${key("legacy")}:A:0"))))
        val orbit = OrbitWatchHistory(context)
        fun orbitSelection(config: String) = OrbitWatchSelection(
            config, "channel", OrbitVideoItem("42", "Orbit 视频", "简介", "", JSONObject()),
            OrbitEpisodeItem("episode", "第 1 集", JSONObject()))
        orbit.save(orbitSelection("plugin-a"), 15_000, 60_000)
        orbit.save(orbitSelection("plugin-b"), 25_000, 60_000)
        assertEquals(15_000L, orbit.entries("plugin-a").single().positionMs)
        assertEquals(25_000L, orbit.entries("plugin-b").single().positionMs)
        engine.rebuildSnapshot(SyncTrigger.MANUAL_PULL) { _, _, _, _ -> page() }
        assertEquals(0, count("watch_history", "video_id=?", arrayOf(video("confirmed").id)))
        assertEquals(0, count("episode_progress", "video_id=?", arrayOf(video("confirmed").id)))
        assertEquals(1, count("watch_history", "video_id=?", arrayOf(video("pending").id)))
        assertNotNull(db.readSyncOutboxItem("progress", pending.key))
        assertEquals(1, count("watch_history", "video_id=?", arrayOf(video("legacy").id)))
        assertEquals(15_000L, orbit.entries("plugin-a").single().positionMs)
        assertEquals(25_000L, orbit.entries("plugin-b").single().positionMs)
        assertTrue(repo.watchHistory().none { it.video.id.startsWith("orbit:") })
        assertTrue(db.readSyncState("watch_snapshot_hold:${key("legacy")}").isNotBlank())
        repo.enqueueLocalSyncSnapshot()
        assertNull(db.readSyncOutboxItem("progress", "${key("legacy")}:A:0"))
        assertTrue(db.readSyncOutbox(Int.MAX_VALUE).none { it.key.contains("orbit:") })
    }

    @Test fun interruptedSecondPageRestartsAtFirstWithoutEarlyDelete() = runBlocking {
        val confirmed = save("42")
        repo.recordConfirmedWatch(listOf(confirmed))
        db.clearSyncOutbox()
        val cursors = mutableListOf<Long>()
        engine.rebuildSnapshot(SyncTrigger.MANUAL_PULL) { _, cursor, _, _ ->
            cursors += cursor
            assertEquals(1, count("watch_history", "video_id=?", arrayOf(video("42").id)))
            when (cursors.size) {
                1 -> page(next = 5, more = true)
                2 -> invalidated()
                else -> page()
            }
        }
        assertEquals(listOf(0L, 5L, 0L), cursors)
        assertEquals(0, count("watch_history", "video_id=?", arrayOf(video("42").id)))
    }

    @Test fun syncSnapshotThenServerDeletionRebuildRemovesPreviouslyConfirmed() = runBlocking {
        val local = save("episode")
        db.clearSyncOutbox()
        engine.rebuildSnapshot(SyncTrigger.MANUAL_PULL) { _, _, _, _ -> page(listOf(local)) }
        assertEquals(1, count("watch_history", "video_id=?", arrayOf(video("episode").id)))
        assertEquals(local.updatedAt.toString(), db.readSyncState("watch_confirmed:${key("episode")}"))
        engine.rebuildSnapshot(SyncTrigger.MANUAL_PULL) { _, _, _, _ -> page() }
        assertEquals(0, count("watch_history", "video_id=?", arrayOf(video("episode").id)))
        assertEquals(0, count("episode_progress", "video_id=?", arrayOf(video("episode").id)))
    }
    @Test fun repeatedFailureNeverAppliesPartialSnapshot() = runBlocking {
        val confirmed = save("failed")
        repo.recordConfirmedWatch(listOf(confirmed))
        db.clearSyncOutbox()
        val cursors = mutableListOf<Long>()
        try {
            engine.rebuildSnapshot(SyncTrigger.MANUAL_PULL) { _, cursor, _, _ ->
                cursors += cursor
                if (cursor == 0L) page(next = 5, more = true) else invalidated()
            }
            fail("rebuild must fail after three invalidated attempts")
        } catch (error: HubApiException) {
            assertEquals(HubFailure.ResyncRequired, error.failure)
        }
        assertEquals(listOf(0L, 5L, 0L, 5L, 0L, 5L), cursors)
        assertEquals(1, count("watch_history", "video_id=?", arrayOf(video("failed").id)))
        assertEquals(1, count("episode_progress", "video_id=?", arrayOf(video("failed").id)))
    }

    @Test fun pendingNewerProgressProtectsPreviouslyConfirmedHistory() = runBlocking {
        val confirmed = save("changed")
        repo.recordConfirmedWatch(listOf(confirmed))
        db.clearSyncOutbox()
        android.os.SystemClock.sleep(5)
        val newer = save("changed")
        assertTrue(newer.updatedAt > confirmed.updatedAt)
        engine.rebuildSnapshot(SyncTrigger.MANUAL_PULL) { _, _, _, _ -> page() }
        assertEquals(1, count("watch_history", "video_id=?", arrayOf(video("changed").id)))
        assertEquals(1, count("episode_progress", "video_id=?", arrayOf(video("changed").id)))
        assertNotNull(db.readSyncOutboxItem("progress", newer.key))
        assertTrue(db.readSyncState("watch_snapshot_hold:${key("changed")}").isNotBlank())
    }

}
