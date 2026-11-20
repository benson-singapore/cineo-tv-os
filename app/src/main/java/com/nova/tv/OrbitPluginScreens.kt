package com.nova.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import android.os.SystemClock
import androidx.tv.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val OrbitInk = Color(0xFF090D13)
private val OrbitMuted = Color(0xFFB5C0CC)
private val OrbitGold = Color(0xFFE9B44C)

@Composable
internal fun OrbitPluginScreen(
    repository: OrbitPluginRepository,
    entryFocusRequester: FocusRequester,
    menuFocusRequester: FocusRequester,
    history: OrbitWatchHistory,
    onPlay: (RemoteVideo, PlaybackLine, PlaybackEpisode, FocusRequester, OrbitWatchSelection, Long, () -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val previewPreferences = remember(context) { context.getSharedPreferences("orbit_preview", android.content.Context.MODE_PRIVATE) }
    var previewColumns by remember { mutableIntStateOf(previewPreferences.getInt("columns", 4).coerceIn(2, 6)) }
    val ratios = listOf("3:4", "4:3", "16:9", "9:16", "1:1")
    var previewRatio by remember { mutableStateOf(previewPreferences.getString("ratio", "3:4")?.takeIf { it in ratios } ?: "3:4") }
    val posterRatio = when (previewRatio) {
        "4:3" -> 4f / 3f
        "16:9" -> 16f / 9f
        "9:16" -> 9f / 16f
        "1:1" -> 1f
        else -> 3f / 4f
    }
    var showPreviewConfig by remember { mutableStateOf(false) }
    val previewFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var showPluginPicker by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var historyRevision by remember { mutableIntStateOf(0) }
    var historyOrigin by remember { mutableStateOf(false) }
    var pendingHistoryEntry by remember { mutableStateOf<OrbitWatchEntry?>(null) }
    var resumeEntry by remember { mutableStateOf<OrbitWatchEntry?>(null) }
    val historyFocus = remember { FocusRequester() }
    val pickerFocus = remember { FocusRequester() }
    val historyFirstFocus = remember { FocusRequester() }
    var plugins by remember { mutableStateOf(emptyList<OrbitPluginConfig>()) }
    val channelFocus = remember(plugins) { plugins.associate { "${it.id}:${it.channelId}" to FocusRequester() } }
    val firstPosterFocus = remember { FocusRequester() }
    val refreshFocus = remember { FocusRequester() }
    var selected by remember { mutableStateOf<OrbitPluginConfig?>(null) }
    var channel by remember { mutableStateOf<OrbitChannel?>(null) }
    var content by remember { mutableStateOf(emptyList<OrbitVideoItem>()) }
    val gridState = rememberLazyGridState()
    val posterFocus = remember(content) { content.associate { it.id to FocusRequester() } }
    var returnFocusId by remember { mutableStateOf<String?>(null) }
    var restoreListFocus by remember { mutableStateOf(false) }
    var next by remember { mutableStateOf<String?>(null) }
    var chapters by remember { mutableStateOf(emptyList<OrbitEpisodeItem>()) }
    var chaptersNext by remember { mutableStateOf<String?>(null) }
    var selectedVideo by remember { mutableStateOf<OrbitVideoItem?>(null) }
    var selectedEpisode by remember { mutableStateOf<OrbitEpisodeItem?>(null) }
    var loading by remember { mutableStateOf(false) }
    var playbackLoading by remember { mutableStateOf(false) }
    var playbackLoadingFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val playbackCache = remember { mutableMapOf<String, Pair<Long, OrbitPlayback>>() }
    val chapterCache = remember { mutableMapOf<String, Triple<List<OrbitEpisodeItem>, String?, OrbitPageContext>>() }
    var loadingMore by remember { mutableStateOf(false) }
    var detailLoading by remember { mutableStateOf(false) }
    var detailFocusPending by remember { mutableStateOf(false) }
    val detailPlayFocus = remember { FocusRequester() }
    val detailBackFocus = remember { FocusRequester() }
    val loadingBackFocus = remember { FocusRequester() }
    var pluginError by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var capabilitiesRetry by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var activeQuery by remember { mutableStateOf("") }
    var appliedFilters by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var requestGeneration by remember { mutableIntStateOf(0) }
    var pluginGeneration by remember { mutableIntStateOf(0) }
    var listContext by remember { mutableStateOf<OrbitPageContext?>(null) }
    var chapterContext by remember { mutableStateOf<OrbitPageContext?>(null) }

    fun remoteVideo(item: OrbitVideoItem, config: OrbitPluginConfig): RemoteVideo = RemoteVideo(
        id = "orbit:${config.id}:${item.id}", sourceId = config.id, sourceName = config.name,
        remoteId = item.id, title = item.title, category = channel?.label.orEmpty(),
        year = "", description = item.summary, posterUrl = item.imageUrl, rating = ""
    )

    fun baseContext(config: OrbitPluginConfig, op: OrbitOperation, queryText: String = "", target: Map<String, String> = emptyMap()) =
        OrbitPageContext(
            configId = config.id, channelId = config.channelId, operation = op.operation,
            scope = op.scope, query = queryText,
            params = ((channel?.defaults.orEmpty() + op.defaults + appliedFilters)
                .filterKeys { it in op.required || it in op.optional } - "query"),
            target = target, next = null
        )

    fun loadPlugins() {
        val generation = ++pluginGeneration
        pluginError = null
        loading = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repository.listPlugins() }
                if (generation == pluginGeneration) {
                    plugins = result
                    selected = selected?.let { current -> result.firstOrNull { it.id == current.id && it.channelId == current.channelId } } ?: result.firstOrNull()
                    if (result.isEmpty()) pluginError = "Hub 目前没有启用的 Orbit 插件"
                }
            } catch (failure: Exception) {
                if (generation == pluginGeneration) pluginError = failure.message ?: "无法读取 Hub 插件配置"
            } finally { if (generation == pluginGeneration) loading = false }
        }
    }

    LaunchedEffect(retry) { loadPlugins() }

    fun loadFirstPage(searchText: String = activeQuery) {
        val config = selected ?: return
        val currentChannel = channel ?: return
        val searchOperation = currentChannel.operations.firstOrNull { it.operation == "search" && it.executable }
        if (searchText.isNotBlank() && searchOperation == null) {
            actionError = "此配置绑定的频道未声明搜索能力；可选择 Hub 中绑定搜索频道的配置。"
            return
        }
        val feedOperation = when {
            searchText.isNotBlank() && searchOperation != null -> searchOperation
            else -> currentChannel.operations.firstOrNull { it.operation == "feed" && it.executable }
                ?: currentChannel.operations.firstOrNull { it.operation == "list" && it.executable }
                ?: currentChannel.operations.firstOrNull {
                    it.executable && it.operation !in setOf("search", "page", "chapters", "chapterDetail", "detail")
                }
        }
        if (feedOperation == null) {
            actionError = "此频道未声明可执行的列表能力"
            content = emptyList(); next = null
            return
        }
        val generation = ++requestGeneration
        loading = true; loadingMore = false; actionError = null; content = emptyList(); next = null; selectedTag = null
        val context = baseContext(config, feedOperation, searchText)
        listContext = context
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repository.listItems(repository.invoke(config, context)) }
                if (generation == requestGeneration && selected?.id == config.id && selected?.channelId == config.channelId) {
                    content = result.items; next = result.next; listContext = context.copy(next = result.next)
                    if (result.items.isEmpty()) actionError = "此列表目前没有内容"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == requestGeneration) actionError = failure.message ?: "加载 Orbit 列表失败" }
            finally { if (generation == requestGeneration) loading = false }
        }
    }

    LaunchedEffect(selected?.id, selected?.channelId, capabilitiesRetry) {
        val config = selected ?: return@LaunchedEffect
        val generation = ++requestGeneration
        channel = null; content = emptyList(); chapters = emptyList(); selectedVideo = null
        next = null; activeQuery = ""; query = ""; actionError = null; loadingMore = false
        appliedFilters = emptyMap()
        loading = true
        try {
            val caps = withContext(Dispatchers.IO) { repository.capabilities(config) }
            if (generation == requestGeneration && selected?.id == config.id && selected?.channelId == config.channelId) {
                channel = caps
                // loadFirstPage is intentionally called after the capability state is committed.
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (generation == requestGeneration) actionError = failure.message ?: "读取频道能力失败" }
        finally { if (generation == requestGeneration) loading = false }
    }

    LaunchedEffect(channel, selected?.id, selected?.channelId) {
        if (channel?.id == selected?.channelId) loadFirstPage("")
    }

    fun loadMore() {
        val config = selected ?: return
        val currentChannel = channel ?: return
        val previous = listContext ?: return
        val pageOp = currentChannel.operations.firstOrNull { it.operation == "page" && it.executable }
        if (loading || loadingMore || next == null) return
        if (pageOp == null) { actionError = "此频道没有声明列表分页能力"; return }
        val context = paginationContext(previous, pageOp, currentChannel.defaults, appliedFilters, next)
        loadingMore = true; actionError = null
        val generation = requestGeneration
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { repository.listItems(repository.invoke(config, context)) }
                if (generation == requestGeneration && selected?.id == config.id && listContext?.next == previous.next) {
                    content = (content + page.items).distinctBy { it.id }
                    next = page.next
                    listContext = context.copy(next = page.next)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == requestGeneration) actionError = failure.message ?: "加载下一页失败" }
            finally { if (generation == requestGeneration) loadingMore = false }
        }
    }

    fun openVideo(item: OrbitVideoItem) {
        if (loading) return
        val config = selected ?: return
        val op = channel?.operations?.firstOrNull { it.operation == "chapters" && it.executable }
        if (op == null) { actionError = "此频道未声明选集能力"; return }
        val targetKey = op.targets.keys.firstOrNull() ?: run { actionError = "章节能力缺少 target 声明"; return }
        val context = baseContext(config, op, activeQuery, mapOf(targetKey to item.id))
        val cacheKey = "${config.id}:${config.channelId}:${item.id}"
        val cached = chapterCache[cacheKey]
        returnFocusId = item.id
        selectedVideo = item; selectedEpisode = null; chapters = emptyList(); chaptersNext = null; chapterContext = context
        actionError = null; detailFocusPending = true; loadingMore = false
        if (cached != null) {
            ++requestGeneration
            chapters = cached.first; chaptersNext = cached.second; chapterContext = cached.third
            loading = false; detailLoading = false
            return
        }
        loading = true; detailLoading = true
        val generation = ++requestGeneration
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { repository.invoke(config, context) }
                val result = repository.listEpisodes(raw)
                if (generation == requestGeneration && selectedVideo?.id == item.id) {
                    chapters = result
                    chaptersNext = raw.optJSONObject("next")?.toString().takeIf { raw.optBoolean("hasMore", true) }
                    chapterContext = context.copy(next = chaptersNext)
                    chapterCache[cacheKey] = Triple(chapters, chaptersNext, chapterContext!!)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == requestGeneration) actionError = failure.message ?: "读取选集失败" }
            finally { if (generation == requestGeneration) { loading = false; detailLoading = false } }
        }
    }

    LaunchedEffect(channel, loading, pendingHistoryEntry) {
        val entry = pendingHistoryEntry ?: return@LaunchedEffect
        if (!loading && channel?.id == entry.selection.channelId) {
            pendingHistoryEntry = null
            historyOrigin = true
            resumeEntry = entry
            openVideo(entry.selection.video)
        }
    }

    fun loadMoreChapters() {
        val config = selected ?: return
        val previous = chapterContext ?: return
        val cursor = chaptersNext ?: return
        val currentChannel = channel ?: return
        val pageOp = currentChannel.operations.firstOrNull { it.operation == "page" && it.executable &&
            (it.scope == "chapters" || it.pagination?.scope == "chapters") }
        if (pageOp == null) { actionError = "此频道未声明章节分页能力"; return }
        if (loading || loadingMore) return
        val context = paginationContext(previous, pageOp, currentChannel.defaults, appliedFilters, cursor)
        loadingMore = true
        val generation = requestGeneration
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { repository.invoke(config, context) }
                val page = repository.listEpisodes(raw)
                if (generation == requestGeneration && selectedVideo?.id == previous.target.values.firstOrNull()) {
                    chapters = (chapters + page).distinctBy { it.id }
                    chaptersNext = raw.optJSONObject("next")?.toString().takeIf { raw.optBoolean("hasMore", true) }
                    chapterContext = context.copy(next = chaptersNext)
                    chapterCache["${config.id}:${config.channelId}:${selectedVideo?.id}"] = Triple(chapters, chaptersNext, chapterContext!!)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == requestGeneration) actionError = failure.message ?: "加载章节下一页失败" }
            finally { if (generation == requestGeneration) loadingMore = false }
        }
    }

    fun playEpisode(episode: OrbitEpisodeItem, returnFocus: FocusRequester) {
        if (loading) return
        val config = selected ?: return
        val video = selectedVideo ?: return
        val op = channel?.operations?.firstOrNull { it.operation == "chapterDetail" && it.executable }
        if (op == null) { actionError = "此频道未声明章节播放信息能力"; return }
        val parentKey = op.targets.keys.firstOrNull() ?: "itemId"
        val chapterKey = op.targets.keys.firstOrNull { it != parentKey } ?: run { actionError = "章节详情缺少章节 target 声明"; return }
        val context = baseContext(config, op, activeQuery, mapOf(parentKey to video.id, chapterKey to episode.id))
        selectedEpisode = episode
        loading = true; playbackLoading = true; playbackLoadingFocus = returnFocus; actionError = null
        val cacheKey = "${config.id}:${config.channelId}:${video.id}:${episode.id}"
        val generation = ++requestGeneration
        scope.launch {
            try {
                val cached = playbackCache[cacheKey]?.takeIf { SystemClock.elapsedRealtime() - it.first < 5 * 60_000L }
                val playback = cached?.second ?: withContext(Dispatchers.IO) { repository.playback(repository.invoke(config, context)) }
                if (cached == null && generation == requestGeneration) playbackCache[cacheKey] = SystemClock.elapsedRealtime() to playback
                if (generation == requestGeneration && selectedVideo?.id == video.id) {
                    actionError = null
                    val record = history.entries(config.id).firstOrNull {
                        it.selection.channelId == config.channelId && it.selection.video.id == video.id && it.selection.episode.id == episode.id
                    }
                    val position = record?.let { if (it.durationMs > 0 && it.positionMs >= it.durationMs - 10_000) 0L else it.positionMs } ?: 0L
                    onPlay(remoteVideo(video, config), PlaybackLine("Orbit", listOf(PlaybackEpisode(playback.title.ifBlank { episode.title }, playback.url))), PlaybackEpisode(playback.title.ifBlank { episode.title }, playback.url), returnFocus, OrbitWatchSelection(config.id, config.channelId, video, episode), position) { playbackCache.remove(cacheKey) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == requestGeneration) actionError = failure.message ?: "无法播放此章节" }
            finally {
                if (generation == requestGeneration) {
                    loading = false; playbackLoading = false
                    if (actionError != null) { withFrameNanos { }; runCatching { returnFocus.requestFocus() } }
                }
            }
        }
    }

    fun returnToList() {
        requestGeneration++
        loading = false; playbackLoading = false; detailLoading = false; detailFocusPending = false; loadingMore = false
        selectedVideo = null; selectedEpisode = null
        chapters = emptyList(); chaptersNext = null; actionError = null
        if (historyOrigin) {
            showHistory = true
            historyOrigin = false
            historyRevision++
        } else restoreListFocus = true
    }

    LaunchedEffect(selectedVideo, restoreListFocus) {
        if (selectedVideo == null && restoreListFocus) {
            val visibleItems = content.filter { item ->
                selectedTag == null || item.raw.optJSONArray("tags")?.let { arr ->
                    (0 until arr.length()).any { arr.optString(it) == selectedTag }
                } == true
            }
            val index = visibleItems.indexOfFirst { it.id == returnFocusId }
            if (index >= 0) gridState.scrollToItem(index)
            withFrameNanos { }
            val restored = runCatching { returnFocusId?.let { posterFocus[it]?.requestFocus() } == true }.getOrDefault(false)
            if (!restored) runCatching { entryFocusRequester.requestFocus() }
            restoreListFocus = false
        }
    }
    LaunchedEffect(selectedVideo?.id, detailLoading, detailFocusPending) {
        if (selectedVideo != null && detailFocusPending) {
            withFrameNanos { }
            if (detailLoading) {
                runCatching { loadingBackFocus.requestFocus() }
            } else {
                val target = if (chapters.isNotEmpty()) detailPlayFocus else detailBackFocus
                runCatching { target.requestFocus() }
                detailFocusPending = false
            }
        }
    }
    LaunchedEffect(showHistory, selectedVideo) {
        if (showHistory && selectedVideo == null) {
            withFrameNanos { }
            if (!runCatching { historyFirstFocus.requestFocus() }.getOrDefault(false)) runCatching { historyFocus.requestFocus() }
        }
    }
    BackHandler(enabled = selectedVideo != null) { returnToList() }
    BackHandler(enabled = showHistory && selectedVideo == null) {
        showHistory = false
        scope.launch { withFrameNanos { }; runCatching { historyFocus.requestFocus() } }
    }

    val tags = remember(content) { content.flatMap { item ->
        val values = item.raw.optJSONArray("tags") ?: return@flatMap emptyList()
        (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
    }.distinct() }

    val currentHistory = remember(selected?.id, selected?.channelId, historyRevision, showHistory) {
        selected?.let { config -> history.entries(config.id) }.orEmpty()
    }
    val hasVisibleContent = content.any { item ->
        selectedTag == null || item.raw.optJSONArray("tags")?.let { arr ->
            (0 until arr.length()).any { arr.optString(it) == selectedTag }
        } == true
    }
    val listHasEntry = if (showHistory) currentHistory.isNotEmpty() else hasVisibleContent
    if (playbackLoading) {
        fun cancelPlaybackLoading() {
            requestGeneration++
            loading = false
            playbackLoading = false
            scope.launch { withFrameNanos { }; runCatching { playbackLoadingFocus?.requestFocus() } }
        }
        val cancelFocus = remember { FocusRequester() }
        Dialog(onDismissRequest = { cancelPlaybackLoading() }) {
            LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; runCatching { cancelFocus.requestFocus() } }
            Column(Modifier.width(360.dp).background(Color(0xFF18212D), RoundedCornerShape(16.dp)).padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                val rotation by rememberInfiniteTransition(label = "loading").animateFloat(
                    initialValue = 0f, targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "rotation")
                Canvas(Modifier.size(32.dp)) { drawArc(OrbitGold, rotation, 260f, false, style = Stroke(width = 3.dp.toPx())) }
                Spacer(Modifier.height(16.dp))
                Text("正在获取播放地址…", color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.height(18.dp))
                OrbitAction("取消", modifier = Modifier.focusRequester(cancelFocus)) { cancelPlaybackLoading() }
            }
        }
    }
    if (showPluginPicker) {
        val pickerEntry = remember { FocusRequester() }
        Dialog(onDismissRequest = { showPluginPicker = false; scope.launch { withFrameNanos { }; runCatching { pickerFocus.requestFocus() } } }) {
            LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; runCatching { pickerEntry.requestFocus() } }
            LazyColumn(Modifier.width(420.dp).heightIn(max = 500.dp).background(Color(0xFF18212D), RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("切换插件", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                itemsIndexed(plugins.distinctBy { it.id }) { index, config ->
                    OrbitAction(config.name, selected = config.id == selected?.id,
                        modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(pickerEntry) else Modifier)) {
                        showPluginPicker = false
                        showHistory = false
                        if (config.id != selected?.id) {
                            requestGeneration++
                            channel = null; content = emptyList(); next = null; listContext = null
                            loading = true; actionError = null; showHistory = false; selected = config
                        }
                        scope.launch { withFrameNanos { }; runCatching { pickerFocus.requestFocus() } }
                    }
                }
            }
        }
    }
    if (showPreviewConfig) {
        val columnFocus = remember { (2..6).associateWith { FocusRequester() } }
        val ratioFocus = remember { ratios.associateWith { FocusRequester() } }
        val doneFocus = remember { FocusRequester() }
        fun closePreview() {
            showPreviewConfig = false
            scope.launch { withFrameNanos { }; runCatching { previewFocus.requestFocus() } }
        }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { columnFocus.getValue(previewColumns).requestFocus() } }
        Dialog(onDismissRequest = { closePreview() }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.width(620.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF18212D)).padding(26.dp)) {
                Text("预览配置", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                Text("更改当前列表每行列数和封面比例", color = OrbitMuted, fontSize = 12.sp)
                Spacer(Modifier.height(24.dp))
                Text("每行展示", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (2..6).forEach { count ->
                        OrbitAction("$count 列", selected = previewColumns == count,
                            modifier = Modifier.focusRequester(columnFocus.getValue(count)).focusProperties { down = ratioFocus.getValue(previewRatio) }) {
                            previewColumns = count
                            previewPreferences.edit().putInt("columns", count).apply()
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text("封面比例", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ratios.forEach { ratio ->
                        OrbitAction(ratio, selected = previewRatio == ratio,
                            modifier = Modifier.focusRequester(ratioFocus.getValue(ratio)).focusProperties {
                                up = columnFocus.getValue(previewColumns); down = doneFocus
                            }) {
                            previewRatio = ratio
                            previewPreferences.edit().putString("ratio", ratio).apply()
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OrbitAction("完成", primary = true, modifier = Modifier.focusRequester(doneFocus).focusProperties {
                        up = ratioFocus.getValue(previewRatio)
                    }) { closePreview() }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(OrbitInk).padding(start = 108.dp, end = 40.dp, top = 28.dp, bottom = 24.dp)) {
        if (selectedVideo == null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(selected?.name ?: "Orbit 插件", modifier = Modifier.weight(1f, fill = false), color = Color.White,
                        fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    var pickerFocused by remember { mutableStateOf(false) }
                    Box(Modifier.focusRequester(pickerFocus).focusProperties { left = menuFocusRequester }.onFocusChanged { pickerFocused = it.isFocused }
                        .background(if (pickerFocused) Color(0xFF344354) else Color.Transparent, RoundedCornerShape(24.dp))
                        .clickable { showPluginPicker = true }.padding(10.dp)) { Image(Icons.Default.SwapHoriz, contentDescription = "切换插件", colorFilter = ColorFilter.tint(Color.White)) }
                }
                Text("经绑定的 Cineo Hub 安全浏览", color = OrbitMuted, fontSize = 13.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OrbitAction("预览配置", modifier = Modifier.focusRequester(previewFocus)) { showPreviewConfig = true }
                var historyFocused by remember { mutableStateOf(false) }
                Box(Modifier.focusRequester(historyFocus).onFocusChanged { historyFocused = it.isFocused }
                    .background(if (historyFocused) Color(0xFF344354) else Color.Transparent, RoundedCornerShape(24.dp)).clickable {
                    historyRevision++; showHistory = true
                }.padding(12.dp)) { Image(Icons.Default.History, contentDescription = "播放历史", colorFilter = ColorFilter.tint(Color.White)) }
                OrbitAction("刷新", primary = true, modifier = Modifier.focusRequester(refreshFocus).then(if (!listHasEntry) Modifier.focusRequester(entryFocusRequester) else Modifier).focusProperties {
                    channelFocus["${selected?.id}:${selected?.channelId}"]?.let { down = it }
                }) { chapterCache.clear(); playbackCache.clear(); retry++ }
            }
        }
        Spacer(Modifier.height(18.dp))
        if (plugins.isNotEmpty()) {
            LazyRow(modifier = Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(plugins.filter { it.id == selected?.id }, key = { "${it.id}:${it.channelId}" }) { config ->
                    OrbitAction(config.channelLabel, modifier = Modifier
                        .focusRequester(channelFocus.getValue("${config.id}:${config.channelId}"))
                        .focusProperties {
                            left = menuFocusRequester
                            up = refreshFocus
                            if (content.isNotEmpty() && selectedVideo == null) down = firstPosterFocus
                        }, selected = config.id == selected?.id && config.channelId == selected?.channelId) {
                        if (config.id != selected?.id || config.channelId != selected?.channelId) {
                            requestGeneration++
                            channel = null; content = emptyList(); next = null; listContext = null
                            loading = true; actionError = null; showHistory = false; selected = config
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        }
        when {
            pluginError != null -> OrbitMessage(pluginError!!, if (plugins.isEmpty()) "重试" else null) { retry++ }
            plugins.isEmpty() && loading -> OrbitMessage("正在读取 Hub 插件配置…")
            plugins.isEmpty() -> OrbitMessage("Hub 尚未绑定或没有启用 Orbit 插件。绑定账号并在 Hub 管理端启用插件后重试。", "重试") { retry++ }
            channel == null && loading -> OrbitMessage("正在读取 ${selected?.name.orEmpty()} 能力…")
            channel == null && actionError != null -> OrbitMessage(actionError!!, "重试") { capabilitiesRetry++ }
            selectedVideo != null && detailLoading -> {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text("正在加载详情和选集…", color = Color.White, fontSize = 20.sp)
                    Spacer(Modifier.height(20.dp))
                    OrbitAction("返回列表", modifier = Modifier.focusRequester(entryFocusRequester).focusRequester(loadingBackFocus)) { returnToList() }
                }
            }
            selectedVideo != null -> {
                val video = selectedVideo!!
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    item {
                        Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp))) {
                            RemoteArtwork(video.imageUrl, video.title, Modifier.fillMaxSize())
                            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(OrbitInk, OrbitInk.copy(alpha = .55f), Color.Transparent))))
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, OrbitInk))))
                            Column(Modifier.align(Alignment.CenterStart).padding(26.dp).fillMaxWidth(.7f)) {
                                Text(video.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(12.dp))
                                Text(channel?.label.orEmpty(), color = OrbitGold, fontSize = 15.sp)
                                Text("播放来源：${selected?.name.orEmpty()}", color = OrbitMuted, fontSize = 13.sp)
                                Spacer(Modifier.height(22.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    (chapters.firstOrNull { it.id == resumeEntry?.selection?.episode?.id } ?: chapters.firstOrNull())?.let { first ->
                                        OrbitAction(if (loading) "正在准备播放…" else if (resumeEntry != null) "▶  继续播放" else "▶  立即播放", primary = true, modifier = Modifier.focusRequester(entryFocusRequester).focusRequester(detailPlayFocus)) { playEpisode(first, detailPlayFocus) }
                                    }
                                    OrbitAction("返回列表", modifier = Modifier.then(if (chapters.isEmpty()) Modifier.focusRequester(entryFocusRequester) else Modifier).focusRequester(detailBackFocus)) { returnToList() }
                                }
                            }
                        }
                    }
                    if (video.summary.isNotBlank()) item {
                        Text("简介", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Text(video.summary, color = OrbitMuted, fontSize = 15.sp)
                    }
                    item {
                        Text("播放来源", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        OrbitAction(selected?.name.orEmpty(), selected = true) { }
                    }
                    item {
                        Text("剧集 · ${chapters.size} 集", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(14.dp))
                        if (loading && chapters.isEmpty()) OrbitMessage("正在读取选集…")
                        else if (chapters.isEmpty() && actionError == null) OrbitMessage("此作品暂无可用选集")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(chapters, key = { it.id }) { episode ->
                                val episodeFocus = remember(episode.id) { FocusRequester() }
                                OrbitPosterCard(episode.title, episode.raw.orbitArtwork().ifBlank { video.imageUrl },
                                    "第 ${chapters.indexOf(episode) + 1} 集", Modifier.width(224.dp).focusRequester(episodeFocus), landscape = true) { playEpisode(episode, episodeFocus) }
                            }
                            if (chaptersNext != null) item {
                                OrbitAction(if (loadingMore) "正在加载…" else "更多选集") { loadMoreChapters() }
                            }
                        }
                    }
                    if (actionError != null) item {
                        OrbitMessage(actionError!!, "重试") { selectedEpisode?.let { playEpisode(it, detailPlayFocus) } ?: openVideo(video) }
                    }
                }
            }
            showHistory -> {
                Column(Modifier.weight(1f)) {
                    Text("播放历史 · ${selected?.name.orEmpty()}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    if (currentHistory.isEmpty()) OrbitMessage("当前插件暂无播放历史")
                    else LazyVerticalGrid(columns = GridCells.Fixed(previewColumns), modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(top = 16.dp)) {
                        itemsIndexed(currentHistory, key = { _, entry -> entry.selection.video.id }) { index, entry ->
                            val minutes = entry.positionMs / 60000
                            OrbitPosterCard(entry.selection.video.title, entry.selection.video.imageUrl,
                                "${plugins.firstOrNull { it.id == entry.selection.configId && it.channelId == entry.selection.channelId }?.channelLabel.orEmpty()} · ${entry.selection.episode.title} · 已看 ${minutes} 分钟",
                                modifier = (if (index == 0) Modifier.focusRequester(entryFocusRequester).focusRequester(historyFirstFocus) else Modifier)
                                    .focusProperties { if (index % previewColumns == 0) left = menuFocusRequester },
                                aspectRatio = posterRatio) {
                                val target = plugins.firstOrNull { it.id == entry.selection.configId && it.channelId == entry.selection.channelId }
                                if (target != null && target.channelId != selected?.channelId) {
                                    pendingHistoryEntry = entry
                                    showHistory = false
                                    selected = target
                                } else if (target != null) {
                                    historyOrigin = true; resumeEntry = entry; showHistory = false
                                    openVideo(entry.selection.video)
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                val canSearch = channel?.operations?.any { it.operation == "search" && it.executable } == true
                val featureSearch = channel?.features?.optJSONObject("search")
                if (canSearch) {
                    var inputFocused by remember(selected?.id, selected?.channelId) { mutableStateOf(false) }
                    PhoneSearchInputSession(inputFocused, query) { query = it }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BasicTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { activeQuery = query.trim(); keyboard?.hide(); loadFirstPage(activeQuery) }),
                            modifier = Modifier.weight(1f).onFocusChanged { inputFocused = it.hasFocus }.background(Color(0xFF18212D), RoundedCornerShape(10.dp)).padding(14.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                            decorationBox = { inner -> if (query.isEmpty()) Text("搜索 ${featureSearch?.optString("param") ?: "内容"}", color = OrbitMuted, fontSize = 14.sp); inner() }
                        )
                        OrbitAction("搜索", primary = true) { activeQuery = query.trim(); keyboard?.hide(); loadFirstPage(activeQuery) }
                        if (activeQuery.isNotBlank()) OrbitAction("清除") { query = ""; activeQuery = ""; loadFirstPage("") }
                    }
                } else if (featureSearch != null) {
                    Text("能力声明不完整：频道标记了搜索，但没有可执行 search operation。", color = OrbitGold, fontSize = 13.sp)
                }
                if (tags.isNotEmpty() && tags.any { it != channel?.label }) {
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { OrbitAction("全部", selected = selectedTag == null) { selectedTag = null } }
                        items(tags) { tag -> OrbitAction(tag, selected = selectedTag == tag) { selectedTag = tag } }
                    }
                }
                Spacer(Modifier.height(12.dp))
                when {
                    loading && content.isEmpty() -> OrbitMessage("正在加载 ${channel?.label.orEmpty()}…")
                    actionError != null && content.isEmpty() -> OrbitMessage(actionError!!, "重试") { loadFirstPage(activeQuery) }
                    content.isEmpty() -> OrbitMessage("暂无内容")
                    else -> {
                        val visible = content.filter { item ->
                            selectedTag == null || item.raw.optJSONArray("tags")?.let { arr ->
                                (0 until arr.length()).any { arr.optString(it) == selectedTag }
                            } == true
                        }
                        Text("${channel?.label.orEmpty()} · ${visible.size} 部", color = OrbitGold, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(previewColumns), modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            itemsIndexed(visible, key = { _, item -> item.id }) { index, item ->
                                val posterModifier = (if (index == gridState.firstVisibleItemIndex) Modifier.focusRequester(entryFocusRequester).focusRequester(firstPosterFocus) else Modifier)
                                    .focusRequester(posterFocus.getValue(item.id))
                                    .focusProperties {
                                        if (index % previewColumns == 0) left = menuFocusRequester
                                        if (index < previewColumns) channelFocus["${selected?.id}:${selected?.channelId}"]?.let { up = it }
                                    }
                                OrbitPosterCard(item.title, item.imageUrl, channel?.label.orEmpty(), modifier = posterModifier, aspectRatio = posterRatio) { resumeEntry = null; historyOrigin = false; openVideo(item) }
                            }
                            if (next != null) item(span = { GridItemSpan(maxLineSpan) }) {
                                OrbitAction(if (loadingMore) "正在加载下一页…" else "加载更多", primary = true, modifier = Modifier.fillMaxWidth()) { loadMore() }
                            }
                        }
                        if (actionError != null) Text(actionError!!, color = OrbitGold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun org.json.JSONObject.orbitArtwork(): String =
    sequenceOf("image", "cover", "poster", "thumbnail").map { optString(it) }
        .firstOrNull { it.startsWith("https://") }.orEmpty()

@Composable
private fun OrbitPosterCard(
    title: String, image: String, subtitle: String, modifier: Modifier = Modifier,
    landscape: Boolean = false, enabled: Boolean = true, aspectRatio: Float = .75f, onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.onFocusChanged { focused = it.isFocused }.clip(shape)
        .background(if (focused) Color(0xFF273545) else Color(0xFF151E29))
        .border(if (focused) 3.dp else 1.dp, if (focused) OrbitGold else Color(0xFF293645), shape)
        .clickable(enabled = enabled, onClick = onClick).padding(4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (landscape) 16f / 9f else aspectRatio).clip(RoundedCornerShape(7.dp))) {
            RemoteArtwork(image, title, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99090D13)))))
            Text(subtitle, Modifier.align(Alignment.BottomStart).padding(8.dp), color = OrbitMuted, fontSize = 11.sp, maxLines = 1)
        }
        Text(title, Modifier.padding(horizontal = 6.dp, vertical = 9.dp), color = if (focused) OrbitGold else Color.White,
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun OrbitMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = OrbitMuted, fontSize = 15.sp)
        if (action != null) { Spacer(Modifier.height(14.dp)); OrbitAction(action, primary = true, onClick = onAction) }
    }
}

@Composable
private fun OrbitAction(
    label: String,
    primary: Boolean = false,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier.onFocusChanged { focused = it.isFocused }
            .background(when { focused -> Color(0xFFE7EBE7); selected || primary -> Color(0xFF344354); else -> Color.Transparent }, shape)
            .border(if (focused || selected) 2.dp else 1.dp, if (focused) OrbitGold else Color(0xFF526171), shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (focused) Color(0xFF172026) else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

