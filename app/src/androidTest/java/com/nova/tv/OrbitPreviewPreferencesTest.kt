package com.nova.tv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbitPreviewPreferencesTest {
    @Test fun settingsAreIsolatedAndLegacySettingsMigrateOnlyOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("orbit_preview_test", Context.MODE_PRIVATE)
        prefs.edit().clear().putInt("columns", 5).putString("ratio", "16:9").commit()
        val store = OrbitPreviewPreferences(prefs)
        assertEquals(OrbitPreviewConfig(5, "16:9"), store.read("hub-a", "first"))
        assertEquals(OrbitPreviewConfig(), store.read("hub-a", "second"))
        store.write("hub-a", "second", OrbitPreviewConfig(3, "1:1"))
        assertEquals(OrbitPreviewConfig(5, "16:9"), store.read("hub-a", "first"))
        assertEquals(OrbitPreviewConfig(3, "1:1"), store.read("hub-a", "second"))
        assertEquals(OrbitPreviewConfig(), store.read("hub-b", "first"))
        store.write("h.x", "y", OrbitPreviewConfig(6, "9:16"))
        store.write("h", "x.y", OrbitPreviewConfig(2, "4:3"))
        assertEquals(OrbitPreviewConfig(6, "9:16"), store.read("h.x", "y"))
        assertEquals(OrbitPreviewConfig(2, "4:3"), store.read("h", "x.y"))
        prefs.edit().clear().commit()
    }
}
