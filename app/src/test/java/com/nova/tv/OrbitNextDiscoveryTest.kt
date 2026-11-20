package com.nova.tv

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrbitNextDiscoveryTest {
    private fun episode(id: String) = OrbitEpisodeItem(id, id, JSONObject())
    private val op = OrbitOperation("page", "page", scope = "chapters")
    private val context = OrbitPageContext("p", "c", "chapters", "chapters", "", emptyMap(), mapOf("itemId" to "v"), null)
    private fun discovery(more: Boolean? = true, operation: OrbitOperation? = op, cursor: String? = "one", fetch: suspend (OrbitPageContext) -> OrbitChapterPage) =
        OrbitNextDiscovery(listOf(episode("1")), context, cursor, more, operation, emptyMap(), emptyMap(), fetch)

    @Test fun advancesAcrossEmptyPageAndResolvesNext() = runBlocking {
        val requested = mutableListOf<String?>()
        val d = discovery { request ->
            requested += request.next
            if (request.next == "one") OrbitChapterPage(emptyList(), "two", true)
            else OrbitChapterPage(listOf(episode("2")), null, false)
        }
        assertEquals("2", d.next("1")?.id)
        assertEquals(listOf("one", "two"), requested)
        assertNull(d.next("2"))
    }
    @Test fun retryDoesNotConsumeFailedRequest() = runBlocking {
        var calls = 0
        val d = discovery { calls++; if (calls == 1) throw IllegalStateException("network") else OrbitChapterPage(listOf(episode("2")), null, false) }
        try { d.next("1"); fail() } catch (_: IllegalStateException) { }
        assertEquals("2", d.next("1")?.id)
        assertEquals(2, calls)
    }
    @Test fun missingCapabilityAndStalledCursorAreUnknown() = runBlocking {
        val missing = discovery(operation = null) { fail(); error("unreachable") }
        try { missing.next("1"); fail() } catch (_: OrbitNextUnknown) { }
        var calls = 0
        val stalled = discovery { calls++; OrbitChapterPage(emptyList(), "one", true) }
        try { stalled.next("1"); fail() } catch (_: OrbitNextUnknown) { }
        assertEquals(1, calls)
    }
    @Test fun onlyExplicitEndIsTerminal() = runBlocking {
        assertNull(discovery(more = false, operation = null) { fail(); error("unreachable") }.next("1"))
        try { discovery(more = null, cursor = null) { fail(); error("unreachable") }.next("1"); fail() } catch (_: OrbitNextUnknown) { }
    }
    @Test fun missingContextAndPageLimitAreUnknown() = runBlocking {
        val noContext = OrbitNextDiscovery(listOf(episode("1")), null, "one", true, op, emptyMap(), emptyMap()) { fail(); error("unreachable") }
        try { noContext.next("1"); fail() } catch (_: OrbitNextUnknown) { }
        var calls = 0
        val bounded = discovery { calls++; OrbitChapterPage(emptyList(), "cursor$calls", true) }
        try { bounded.next("1"); fail() } catch (_: OrbitNextUnknown) { }
        assertEquals(5, calls)
    }
    @Test fun sameUrlUsesDifferentChapterIdentity() {
        val video = OrbitVideoItem("v", "title", "", "", JSONObject())
        val a = OrbitWatchSelection("p", "c", video, episode("1"))
        val b = a.copy(episode = episode("2"))
        assertNotEquals(orbitPlaybackIdentity(a, "https://example.com/a"), orbitPlaybackIdentity(b, "https://example.com/a"))
    }
}
