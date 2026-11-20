package com.nova.tv

internal class OrbitNextUnknown : Exception("无法确认是否还有下一集，请返回选集查看")

internal data class OrbitChapterPage(val episodes: List<OrbitEpisodeItem>, val next: String?, val hasMore: Boolean?)

internal class OrbitNextDiscovery(
    private var episodes: List<OrbitEpisodeItem>,
    private var context: OrbitPageContext?,
    private var cursor: String?,
    private var hasMore: Boolean?,
    private val pageOperation: OrbitOperation?,
    private val defaults: Map<String, String>,
    private val filters: Map<String, String>,
    private val fetch: suspend (OrbitPageContext) -> OrbitChapterPage,
) {
    private val visited = mutableSetOf<OrbitPageContext>()

    suspend fun next(currentId: String): OrbitEpisodeItem? {
        repeat(5) {
            val index = episodes.indexOfFirst { it.id == currentId }
            if (index < 0) throw OrbitNextUnknown()
            episodes.getOrNull(index + 1)?.let { return it }
            if (hasMore == false) return null
            val op = pageOperation ?: throw OrbitNextUnknown()
            val previous = context ?: throw OrbitNextUnknown()
            if (cursor == null && !canOffsetPage(op, hasMore == true)) throw OrbitNextUnknown()
            val request = paginationContext(previous, op, defaults, filters, cursor)
            if (request in visited) throw OrbitNextUnknown()
            val page = fetch(request) // Failed requests may be retried with the same context.
            visited.add(request)
            episodes = (episodes + page.episodes).distinctBy { it.id }
            context = request.copy(next = page.next)
            cursor = page.next
            hasMore = page.hasMore
            val updatedIndex = episodes.indexOfFirst { it.id == currentId }
            episodes.getOrNull(updatedIndex + 1)?.let { return it }
            if (hasMore == false) return null
        }
        throw OrbitNextUnknown()
    }
}

internal fun orbitPlaybackIdentity(selection: OrbitWatchSelection?, url: String): String =
    selection?.let { "orbit:${it.configId.length}:${it.configId}:${it.channelId.length}:${it.channelId}:${it.video.id.length}:${it.video.id}:${it.episode.id}" } ?: url
