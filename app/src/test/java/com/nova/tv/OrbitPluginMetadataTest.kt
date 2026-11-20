package com.nova.tv

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrbitPluginMetadataTest {
    @Test fun optionalFieldsAndOlderHubAreCompatible() {
        val old = parseOrbitPluginConfig(JSONObject("""{"id":"1","name":"Plugin","plugin_id":"p","channel_id":"c"}"""))
        assertEquals("", old.logoUrl)
        assertEquals("", old.description)
        val current = parseOrbitPluginConfig(JSONObject("""{"id":"1","name":"Plugin","plugin_id":"p","channel_id":"c","logo_url":"https://example.com/icon.png","description":"  Description  "}"""))
        assertEquals("https://example.com/icon.png", current.logoUrl)
        assertEquals("Description", current.description)
        assertEquals(current.logoUrl, current.copy(channelId = "other").logoUrl)
    }
    @Test fun rejectsUnsafeLogoUrls() {
        listOf("http://example.com/a", "https://user@example.com/a", "https://example.com/a?key=secret", "https://example.com/a#x", "https:///a", "not a url").forEach {
            assertEquals("", safeOrbitLogoUri(it))
        }
        assertEquals("", safeOrbitLogoUri(null))
        assertEquals("https://example.com/a", safeOrbitLogoUri(" https://example.com/a "))
    }
    @Test fun invalidOptionalValuesUsePlaceholder() {
        val config = parseOrbitPluginConfig(JSONObject("""{"id":"1","name":"x","logo_url":null,"description":42}"""))
        assertEquals("", config.logoUrl)
        assertEquals("", config.description)
    }
}
