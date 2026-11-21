package com.nova.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class OrbitNextUnknown : Exception("无法确认是否还有下一集，请返回选集查看")

internal data class OrbitChapterPage(val episodes: List<OrbitEpisodeItem>, val next: String?, val hasMore: Boolean?)

internal class OrbitNextDiscovery(
    episodes: List<OrbitEpisodeItem>,
    private var context: OrbitPageContext?,
    private var cursor: String?,
    hasMore: Boolean?,
    private val pageOperation: OrbitOperation?,
    private val defaults: Map<String, String>,
    private val filters: Map<String, String>,
    private val fetch: suspend (OrbitPageContext) -> OrbitChapterPage,
) {
    var episodes by mutableStateOf(episodes.distinctBy { it.id })
        private set
    var hasMore by mutableStateOf(hasMore)
        private set
    val canLoadMore: Boolean
        get() = hasMore != false && pageOperation != null && context != null &&
            (cursor != null || canOffsetPage(pageOperation, hasMore == true))
    private val mutex = Mutex()
    private val visited = mutableSetOf<OrbitPageContext>()

    private suspend fun fetchPage() {
        if (hasMore == false) return
        val op = pageOperation ?: throw OrbitNextUnknown()
        val previous = context ?: throw OrbitNextUnknown()
        if (cursor == null && !canOffsetPage(op, hasMore == true)) throw OrbitNextUnknown()
        val request = paginationContext(previous, op, defaults, filters, cursor)
        if (request in visited) throw OrbitNextUnknown()
        val page = fetch(request)
        visited.add(request)
        episodes = (episodes + page.episodes).distinctBy { it.id }
        context = request.copy(next = page.next)
        cursor = page.next
        hasMore = page.hasMore
    }

    suspend fun loadMore() = mutex.withLock { fetchPage() }

    suspend fun next(currentId: String): OrbitEpisodeItem? = mutex.withLock {
        repeat(5) {
            val index = episodes.indexOfFirst { it.id == currentId }
            if (index < 0) throw OrbitNextUnknown()
            episodes.getOrNull(index + 1)?.let { return@withLock it }
            if (hasMore == false) return@withLock null
            fetchPage()
            val updatedIndex = episodes.indexOfFirst { it.id == currentId }
            episodes.getOrNull(updatedIndex + 1)?.let { return@withLock it }
            if (hasMore == false) return@withLock null
        }
        throw OrbitNextUnknown()
    }
}

internal data class OrbitPlaybackSession(
    val discovery: OrbitNextDiscovery,
    val resolve: suspend (OrbitWatchSelection, OrbitEpisodeItem) -> PreparedPlaybackNext,
)

internal fun orbitPlaybackIdentity(selection: OrbitWatchSelection?, url: String): String =
    selection?.let { "orbit:${it.configId.length}:${it.configId}:${it.channelId.length}:${it.channelId}:${it.video.id.length}:${it.video.id}:${it.episode.id}" } ?: url
