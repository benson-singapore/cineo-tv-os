package com.nova.tv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitPluginRepositoryTest {
    @Test fun parsesHubEnvelopeAndChannelScopedCapabilities() {
        val envelope = parseHubDataEnvelope(200, fixture("capabilities.json"))
        val channel = parseOrbitChannel(envelope, "duanju")
        assertEquals("duanju", channel.id)
        assertEquals("ai-duanju", channel.defaults["section"])
        assertEquals(setOf("feed", "page", "chapters", "chapterDetail"), channel.operations.map { it.operation }.toSet())
        assertFalse(channel.operations.any { it.operation == "search" })
        val chapters = channel.operations.first { it.operation == "chapters" }
        assertEquals("itemId", chapters.targets.keys.single())
        val detail = channel.operations.first { it.operation == "chapterDetail" }
        assertEquals(setOf("itemId", "chapterId"), detail.targets.keys)
        val search = parseOrbitChannel(envelope, "search")
        assertTrue(search.operations.any { it.operation == "search" && "query" in it.required })
    }

    @Test fun mapsFeedAndKeepsOpaqueNextForSeparatePageContext() {
        val business = business("feed.json")
        val page = parseOrbitItems(business)
        assertEquals("item-001", page.items.first().id)
        assertEquals("示例作品", page.items.first().title)
        assertEquals("https://example.invalid/poster.jpg", page.items.first().imageUrl)
        val previous = OrbitPageContext("config-a", "duanju", "feed", query = "", params = mapOf("section" to "ai-duanju"), target = mapOf("keep" to "x"), next = page.next)
        val next = nextPageContext(previous, page.next!!, OrbitPagination("offset", "page", "1", false, true))
        assertEquals(previous.params, next?.params)
        assertEquals(previous.target, next?.target)
        assertEquals(JSONObject(page.next!!).toString(), JSONObject(requireNotNull(next?.next)).toString())
        assertEquals("config-a", next?.configId)
        assertEquals("duanju", next?.channelId)
    }

    @Test fun mapsChapterScopeAndPlaybackAddressFromObservedSchema() {
        val episodes = parseOrbitEpisodes(business("chapters.json"))
        assertEquals("chapter-001", episodes.first().id)
        val playback = parseOrbitPlayback(business("chapterDetail.json"))
        assertEquals("第 1 集", playback.title)
        assertEquals("https://example.invalid/video/sample.m3u8", playback.url)
        val chapterPage = OrbitPageContext("config-a", "duanju", "page", scope = "chapters", query = "",
            params = mapOf("section" to "ai-duanju"), target = mapOf("itemId" to "item-001"), next = "{\"page\":\"2\"}")
        val next = nextPageContext(chapterPage, chapterPage.next, OrbitPagination("offset", "page", "1", false, true, "chapters"))
        assertEquals("chapters", next?.scope)
        assertEquals(chapterPage.target, next?.target)
        val pageOperation = OrbitOperation("page", "下一页", optional = setOf("section"), pagination = OrbitPagination("offset", "page", "1", false, true, "chapters"))
        val pageRequest = paginationContext(chapterPage, pageOperation, mapOf("section" to "ai-duanju"), emptyMap(), chapterPage.next)
        assertEquals("chapters", pageRequest.scope)
        assertEquals("item-001", pageRequest.target["itemId"])
        assertEquals("2", JSONObject(requireNotNull(pageRequest.next)).getString("page"))
    }

    @Test fun reportsApiErrorsAndUnknownOrEmptyBusinessStructures() {
        assertThrows(OrbitApiException::class.java) { parseHubDataEnvelope(502, "{\"code\":50200,\"message\":\"upstream\"}") }
        assertThrows(OrbitApiException::class.java) { parseHubDataEnvelope(401, "{\"code\":40100}") }
        assertThrows(OrbitApiException::class.java) { parseHubDataEnvelope(200, "not-json") }
        assertThrows(OrbitApiException::class.java) { parseOrbitItems(JSONObject("{}")) }
        assertThrows(OrbitApiException::class.java) { parseOrbitEpisodes(JSONObject("{\"items\":null}")) }
        assertThrows(OrbitApiException::class.java) { parseOrbitPlayback(JSONObject("{\"items\":[{\"title\":\"x\"}]}")) }
        assertThrows(OrbitApiException::class.java) { parseOrbitChannel(JSONObject("{\"plugin\":{\"executable\":false,\"channels\":[]}}"), "duanju") }
        assertThrows(OrbitApiException::class.java) { parseOrbitChannel(JSONObject("{\"plugin\":{\"channels\":[]}}"), "missing") }
        assertNull(safeMediaUri("http://example.invalid/video.m3u8"))
        assertNull(safeMediaUri("file:///tmp/video.m3u8"))
        assertNull(safeMediaUri("https://user:pass@example.invalid/video.m3u8"))
    }

    @Test fun playbackUriRetainsSignedQueryWithoutHubCredential() {
        val media = safeMediaUri("https://media.example.invalid/a.m3u8?sig=fixture")
        assertEquals("https://media.example.invalid/a.m3u8?sig=fixture", media)
        assertFalse(media.orEmpty().contains("device-token"))
    }

    @Test fun deviceCredentialIsAttachedOnlyToHubDeviceRequest() {
        val request = createOrbitHubRequest(
            "https://hub.example.invalid", "/device/orbit-plugins/config-1/invoke", "device-token-fixture",
            "{\"operation\":\"chapterDetail\"}"
        )
        assertEquals("https://hub.example.invalid/api/v1/device/orbit-plugins/config-1/invoke", request.url)
        assertEquals("Bearer device-token-fixture", request.authorization)
        assertFalse(request.url.contains("device-token-fixture"))
        assertFalse(requireNotNull(request.body).contains("device-token-fixture"))
        assertThrows(IllegalArgumentException::class.java) {
            createOrbitHubRequest("https://hub.example.invalid", "https://orbit.example.invalid/invoke", "device-token-fixture")
        }
    }

    private fun fixture(name: String): String = javaClass.getResourceAsStream("/orbit/$name")!!
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun business(name: String): JSONObject {
        val envelope = parseHubDataEnvelope(200, fixture(name))
        return envelope.getJSONObject("data")
    }
}
