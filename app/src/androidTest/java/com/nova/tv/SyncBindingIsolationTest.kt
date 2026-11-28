package com.nova.tv

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

private class SyncIsolationContext(base: Context, private val prefix: String) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
    override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("$prefix-$name", mode)
    override fun getDatabasePath(name: String): File = File(baseContext.cacheDir, "$prefix-$name")
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
    override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
        SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
}

class SyncBindingIsolationTest {
    private lateinit var context: SyncIsolationContext
    private lateinit var db: CineoDatabase

    @Before fun setup() {
        context = SyncIsolationContext(InstrumentationRegistry.getInstrumentation().targetContext, "sync-isolation-${UUID.randomUUID()}")
        db = CineoDatabase(context)
    }
    @After fun cleanup() {
        db.close()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("cineo_tv.db"))
    }

    @Test fun sameKeySurvivesRebindAndNewRecordIsUploadable() {
        db.activateBinding("first", "device")
        db.enqueueSyncEnvelope("favorite", "same", 1, false, "{\"v\":1}")
        db.beginBindingTransition()
        db.enqueueSyncEnvelope("favorite", "same", 2, false, "{\"v\":2}")
        db.activateBinding("second", "device")
        db.enqueueSyncEnvelope("favorite", "same", 3, false, "{\"v\":3}")
        assertEquals(2, db.quarantinedOutboxCount())
        assertFalse(db.bindingMatches("first"))
        assertTrue(db.bindingMatches("second"))
        assertEquals(3L, db.readSyncOutbox(10, 0, 0).single().updatedAt)
        db.readableDatabase.rawQuery("SELECT updated_at FROM sync_quarantine ORDER BY id", null).use {
            assertTrue(it.moveToFirst()); assertEquals(1L, it.getLong(0))
            assertTrue(it.moveToNext()); assertEquals(2L, it.getLong(0))
        }
    }

    @Test fun interruptedTransitionStaysClosedAcrossReopen() {
        db.activateBinding("first", "device")
        db.enqueueSyncEnvelope("site", "x", 1, false, "{}")
        db.writeSyncState(mapOf("watch_confirmed:x" to "1", "watch_snapshot_hold:x" to "1"))
        db.beginBindingTransition()
        db.close()
        db = CineoDatabase(context)
        assertFalse(db.bindingMatches("first"))
        assertFalse(db.bindingMatches("second"))
        assertEquals(1, db.quarantinedOutboxCount())
        assertEquals("", db.readSyncState("watch_confirmed:x"))
        assertEquals("", db.readSyncState("watch_snapshot_hold:x"))
        db.enqueueSyncEnvelope("site", "x", 2, false, "{}")
        assertEquals(2, db.quarantinedOutboxCount())
    }

    @Test fun replacingOldGenerationPreservesOldValue() {
        db.activateBinding("first", "device")
        db.enqueueSyncEnvelope("favorite", "same", 1, false, "{\"old\":true}")
        db.writeSyncState(mapOf("generation" to "1", "baseline_at" to "5"))
        db.enqueueSyncEnvelope("favorite", "same", 2, false, "{\"new\":true}")
        assertEquals(1, db.quarantinedOutboxCount())
        assertEquals(2L, db.readSyncOutbox(10, 1, 5).single().updatedAt)
    }
    @Test fun migrationPreservesLegacyQueueAndRequiresNewBinding() {
        db.activateBinding("old", "device")
        db.enqueueSyncEnvelope("favorite", "legacy", 7, false, "{}")
        val sql = db.writableDatabase
        sql.execSQL("DROP TABLE sync_quarantine")
        sql.version = 4
        db.close()
        db = CineoDatabase(context)
        assertEquals(1, db.quarantinedOutboxCount())
        assertFalse(db.bindingMatches("old"))
        assertTrue(db.readSyncOutbox().isEmpty())
    }

    @Test fun concurrentEditsDuringTransitionStayQuarantined() {
        db.activateBinding("first", "device")
        val transitioned = java.util.concurrent.CountDownLatch(1)
        val wrote = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            assertTrue(transitioned.await(5, java.util.concurrent.TimeUnit.SECONDS))
            db.enqueueSyncEnvelope("favorite", "same", 2, false, "{}")
            wrote.countDown()
        }
        worker.start()
        db.enqueueSyncEnvelope("favorite", "same", 1, false, "{}")
        db.beginBindingTransition()
        transitioned.countDown()
        assertTrue(wrote.await(5, java.util.concurrent.TimeUnit.SECONDS))
        db.activateBinding("second", "device")
        worker.join()
        db.enqueueSyncEnvelope("favorite", "same", 3, false, "{}")
        assertEquals(2, db.quarantinedOutboxCount())
        assertEquals(3L, db.readSyncOutbox(10, 0, 0).single().updatedAt)
    }

    @Test fun remoteConflictDoesNotRequeueUnownedLocalFavorite() {
        val repo = MacCmsRepository(context)
        val source = VideoSource("sync-test", "Test", "https://isolation.example.test/api")
        val video = RemoteVideo("sync-test:42", source.id, source.name, "42", "Old", "", "", "", "", "")
        repo.saveSource(source)
        repo.setFavorite(video, true)
        db.activateBinding("new-binding", "device")
        val key = requireNotNull(repo.favoriteSyncKey(video))
        repo.applyRemoteSyncEnvelopes(listOf(SyncEnvelope("favorite", key, 1, true, null)), "device")
        assertTrue(repo.isFavorite(video.id))
        assertNull(db.readSyncOutboxItem("favorite", key))
        assertTrue(db.quarantinedOutboxCount() > 0)
        val other = video.copy(id = "sync-test:43", remoteId = "43")
        repo.setFavorite(other, true)
        assertNotNull(db.readSyncOutboxItem("favorite", requireNotNull(repo.favoriteSyncKey(other))))
    }

    private fun archiveFavorite(id: String = "42"): QuarantinedSyncRecord {
        val url = "https://isolation.example.test/api"
        val key = "${hubSourceKey(url)}:$id"
        val payload = org.json.JSONObject().put("source_api", url)
            .put("video", org.json.JSONObject().put("remoteId", id)).toString()
        db.enqueueSyncEnvelope("favorite", key, 123, false, payload)
        return db.quarantinePage(null).first()
    }

    @Test fun recoveryNeedsMatchingBindingAndPreservesOriginal() {
        val record = archiveFavorite()
        db.activateBinding("current", "device")
        assertNotNull(db.restoreQuarantined(record, "old"))
        assertNotNull(db.restoreQuarantined(record.copy(payloadJson = "{}"), "current"))
        assertNull(db.readSyncOutboxItem(record.entity, record.key))
        assertNull(db.restoreQuarantined(record, "current"))
        assertEquals(123L, db.readSyncOutboxItem(record.entity, record.key)?.updatedAt)
        assertEquals(record, db.quarantinePage(null).single())
        assertNotNull(db.restoreQuarantined(record, "current"))
        assertEquals(1, db.quarantinedOutboxCount())
    }

    @Test fun recoveryNeverOverwritesSameKeyAndRejectsDeletionOrUnknownIdentity() {
        val record = archiveFavorite()
        db.activateBinding("current", "device")
        db.enqueueSyncEnvelope(record.entity, record.key, 456, false, "{\"new\":true}")
        assertNotNull(db.restoreQuarantined(record, "current"))
        assertEquals(456L, db.readSyncOutboxItem(record.entity, record.key)?.updatedAt)
        val deleted = record.copy(deleted = true, payloadJson = null)
        assertNotNull(quarantineRestoreError(deleted))
        assertNotNull(quarantineRestoreError(record.copy(payloadJson = "{}")))
        assertNotNull(quarantineRestoreError(record.copy(payloadJson = "{\"source_api\":\"https://isolation.example.test/api\",\"video\":{\"id\":\"orbit:x\",\"remoteId\":\"42\"}}")))
        assertEquals(1, db.quarantinedOutboxCount())
    }

    @Test fun pagesCoverAllRecordsAndStaleQueueIsPreserved() {
        repeat(25) { archiveFavorite(it.toString()) }
        val a = db.quarantinePage(null)
        val b = db.quarantinePage(a.last().id)
        val c = db.quarantinePage(b.last().id)
        assertEquals(25, (a + b + c).map { it.id }.toSet().size)
        db.activateBinding("current", "device")
        db.enqueueSyncEnvelope("favorite", "old", 1, false, "{}")
        db.writeSyncState(mapOf("generation" to "1", "baseline_at" to "4"))
        db.quarantineStaleOutbox(1, 4, "current")
        assertNull(db.readSyncOutboxItem("favorite", "old"))
        assertEquals(26, db.quarantinedOutboxCount())
    }

    @Test fun uncertainCurrentWatchMovesToReviewWithoutBlockingOtherRecords() {
        db.activateBinding("current", "device")
        db.enqueueSyncEnvelope("history", "broken", 1, true, null)
        db.enqueueSyncEnvelope("favorite", "other", 2, false, "{}")
        assertEquals(1, db.untrustedWatchCount(0, 0))
        db.quarantineUntrustedWatch("current")
        assertNull(db.readSyncOutboxItem("history", "broken"))
        assertNotNull(db.readSyncOutboxItem("favorite", "other"))
        val archived = db.quarantinePage(null).single()
        assertTrue(archived.deleted)
        assertNotNull(quarantineRestoreError(archived))
    }

    @Test fun cloudTokenAndSettingsAreScopedAndLocalTokenSurvivesRebindAndRevoke() {
        val settings = TmdbSettingsStore(context)
        val credentials = DeviceCredentialStore(context)
        settings.saveToken("local-test-token")
        credentials.save("https://first.example", "device", "first-device-token")
        db.activateBinding(credentials.bindingInstance, "device")
        val first = credentials.bindingInstance
        assertEquals("local-test-token", settings.readToken())
        settings.applyCloudValues(first, org.json.JSONObject("{\"tmdb_key\":\"cloud-one\",\"preferences\":{\"tmdb_language\":\"en-US\",\"include_adult\":true}}"))
        assertEquals("cloud-one", settings.readToken())
        assertEquals("en-US", settings.cloudSetting("hub.tmdb_language"))
        val lease = settings.tokenLease()!!
        credentials.markRevoked()
        assertFalse(db.bindingMatches(first))
        assertEquals("local-test-token", settings.readToken())
        assertNull(settings.cloudSetting("hub.include_adult"))
        assertFalse(settings.leaseIsCurrent(lease))
        db.beginBindingTransition()
        credentials.clearLocalBinding()
        assertEquals("local-test-token", settings.readToken())
        credentials.save("https://second.example", "device", "second-device-token")
        db.activateBinding(credentials.bindingInstance, "device")
        assertEquals("local-test-token", settings.readToken())
        assertNull(settings.cloudSetting("hub.tmdb_language"))
        settings.applyCloudValues(credentials.bindingInstance, org.json.JSONObject())
        assertEquals("local-test-token", settings.readToken())
        assertEquals("zh-CN", settings.cloudSetting("hub.tmdb_language"))
        assertEquals("false", settings.cloudSetting("hub.include_adult"))
    }

    @Test fun failedCloudCredentialWriteAndInterruptedSwitchFailClosed() {
        val settings = TmdbSettingsStore(context)
        val credentials = DeviceCredentialStore(context)
        credentials.save("https://first.example", "device", "device-token")
        db.activateBinding(credentials.bindingInstance, "device")
        val first = credentials.bindingInstance
        settings.applyCloudValues(first, org.json.JSONObject("{\"tmdb_key\":\"cloud-one\"}"))
        try {
            settings.applyCloudValues(first, org.json.JSONObject("{\"tmdb_key\":\"cloud-two\"}")) { _, _ -> throw IllegalStateException("simulated storage failure") }
            fail("write must fail")
        } catch (_: IllegalStateException) { }
        assertNull(settings.readToken())
        assertNull(settings.cloudSetting("hub.tmdb_language"))
        db.beginBindingTransition()
        credentials.save("https://second.example", "device", "new-token")
        assertNull(settings.readToken())
        db.close()
        db = CineoDatabase(context)
        assertNull(settings.readToken())
        db.activateBinding(credentials.bindingInstance, "device")
        assertNull(settings.readToken())
        assertNull(settings.cloudSetting("hub.include_adult"))
    }

    @Test fun legacyUnscopedCredentialsRemainStoredButAreNotUsed() {
        val prefs = context.getSharedPreferences("cineo_tv_tmdb", Context.MODE_PRIVATE)
        prefs.edit().putString("token_iv", "legacy-iv").putString("token_ciphertext", "legacy-cipher").commit()
        val settings = TmdbSettingsStore(context)
        assertTrue(settings.hasUnscopedLegacyToken())
        assertNull(settings.readToken())
        settings.saveToken("local-token")
        assertEquals("local-token", settings.readToken())
        assertTrue(prefs.contains("token_ciphertext"))
    }

    @Test fun databaseSettingsWriteFailureKeepsCloudValuesUnavailable() {
        val settings = TmdbSettingsStore(context)
        val credentials = DeviceCredentialStore(context)
        credentials.save("https://first.example", "device", "device-token")
        val binding = credentials.bindingInstance
        db.activateBinding(binding, "device")
        settings.applyCloudValues(binding, org.json.JSONObject("{\"tmdb_key\":\"old\"}"))
        db.writableDatabase.execSQL("""CREATE TRIGGER reject_hub_setting BEFORE INSERT ON app_settings
            WHEN NEW.setting_key = 'hub.include_adult' BEGIN SELECT RAISE(ABORT, 'injected failure'); END""".trimIndent())
        try {
            settings.applyCloudValues(binding, org.json.JSONObject("{\"tmdb_key\":\"new\"}"))
            fail("database write must fail")
        } catch (_: Exception) { }
        assertNull(settings.readToken())
        assertNull(settings.cloudSetting("hub.tmdb_language"))
        assertEquals("", db.readSyncState("hub_settings_ready"))
    }

    @Test fun revocationDuringCloudSettingsWriteCannotReactivateOldSettings() {
        val settings = TmdbSettingsStore(context)
        val credentials = DeviceCredentialStore(context)
        credentials.save("https://first.example", "device", "device-token")
        val binding = credentials.bindingInstance
        db.activateBinding(binding, "device")
        settings.applyCloudValues(binding, org.json.JSONObject("{\"tmdb_key\":\"old\"}"))
        try {
            settings.applyCloudValues(binding, org.json.JSONObject("{\"tmdb_key\":\"new\"}")) { _, _ -> credentials.markRevoked() }
            fail("revoked binding must be rejected")
        } catch (_: IllegalStateException) { }
        assertFalse(db.bindingMatches(binding))
        assertNull(settings.readToken())
        assertNull(settings.cloudSetting("hub.include_adult"))
    }

    @Test fun revokedDatabaseFlagBlocksCloudEvenWhenPreferenceStillSaysBound() {
        val settings = TmdbSettingsStore(context)
        val credentials = DeviceCredentialStore(context)
        credentials.save("https://first.example", "device", "device-token")
        val binding = credentials.bindingInstance
        db.activateBinding(binding, "device")
        settings.applyCloudValues(binding, org.json.JSONObject("{\"tmdb_key\":\"same\"}"))
        val lease = settings.tokenLease()!!
        db.invalidateHubSettings(revoked = true)
        assertFalse(credentials.revoked)
        assertFalse(settings.leaseIsCurrent(lease))
        assertNull(settings.readToken())
        try { settings.applyCloudValues(binding, org.json.JSONObject("{\"tmdb_key\":\"same\"}")); fail("revoked") }
        catch (_: IllegalStateException) { }
        assertNull(settings.readToken())
    }

    @Test fun repeatedSameTokenRefreshNeverReactivatesOldLeaseOrCache() = kotlinx.coroutines.runBlocking {
        val settings = TmdbSettingsStore(context)
        val credentials = DeviceCredentialStore(context)
        val repository = TmdbMetadataRepository(settings)
        val video = RemoteVideo("site:42", "site", "Site", "42", "Shared title", "", "", "", "", "")
        credentials.save("https://first.example", "device", "device-token")
        var binding = credentials.bindingInstance
        db.activateBinding(binding, "device")
        val data = org.json.JSONObject("{\"tmdb_key\":\"same\"}")
        settings.applyCloudValues(binding, data)
        val old = settings.tokenLease()!!
        val oldKey = repository.cacheKeyForLease(video, old)
        assertTrue(repository.commitCacheForLease(old, oldKey, "{\"title\":\"old scope\"}", System.currentTimeMillis()))
        assertEquals("old scope", repository.cachedDetails(video)?.title)
        settings.applyCloudValues(binding, data)
        val refreshed = settings.tokenLease()!!
        assertFalse(settings.leaseIsCurrent(old))
        assertNotEquals(old.scope, refreshed.scope)
        assertFalse(repository.commitCacheForLease(old, oldKey, "{\"stale\":true}", 2))
        assertEquals("{\"title\":\"old scope\"}", db.readTmdbMetadataCache(oldKey)?.first)
        assertNotEquals(oldKey, repository.cacheKeyForLease(video, refreshed))
        assertNull(repository.cachedDetails(video))
        db.beginBindingTransition()
        credentials.save("https://second.example", "device", "other-device-token")
        binding = credentials.bindingInstance
        db.activateBinding(binding, "device")
        settings.applyCloudValues(binding, data)
        val second = settings.tokenLease()!!
        assertNotEquals(refreshed.scope, second.scope)
        assertNull(db.readTmdbMetadataCache(repository.cacheKeyForLease(video, second)))
        assertNull(repository.cachedDetails(video))
        assertFalse(repository.commitCacheForLease(refreshed, repository.cacheKeyForLease(video, refreshed), "{}", 3))
    }

    @Test fun cacheCommitWaitingBehindRevocationIsRejected() {
        val settings = TmdbSettingsStore(context)
        val credentials = DeviceCredentialStore(context)
        val repository = TmdbMetadataRepository(settings)
        credentials.save("https://first.example", "device", "device-token")
        val binding = credentials.bindingInstance
        db.activateBinding(binding, "device")
        settings.applyCloudValues(binding, org.json.JSONObject("{\"tmdb_key\":\"old\"}"))
        val lease = settings.tokenLease()!!
        val ready = java.util.concurrent.CountDownLatch(1)
        var accepted: Boolean? = null
        val worker = Thread {
            ready.countDown()
            accepted = repository.commitCacheForLease(lease, "race-test", "{}", 1)
        }
        synchronized(TmdbAccessGate.lock) {
            worker.start()
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
            db.invalidateHubSettings(revoked = true)
        }
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals(false, accepted)
        assertNull(db.readTmdbMetadataCache("race-test"))
    }

    @Test fun interruptedTransitionInvalidatesLocalFallbackLeaseEvenBeforeCredentialsChange() {
        val settings = TmdbSettingsStore(context)
        val credentials = DeviceCredentialStore(context)
        settings.saveToken("local-token")
        credentials.save("https://first.example", "device", "device-token")
        db.activateBinding(credentials.bindingInstance, "device")
        val lease = settings.tokenLease()!!
        assertEquals("local", lease.source)
        db.beginBindingTransition()
        assertFalse(settings.leaseIsCurrent(lease))
        assertEquals("local-token", settings.readToken())
        assertNotEquals(lease.scope, settings.tokenLease()!!.scope)
    }

}
