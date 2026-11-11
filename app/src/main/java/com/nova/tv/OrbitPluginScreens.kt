package com.nova.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    onPlay: (RemoteVideo, PlaybackLine, PlaybackEpisode) -> Unit
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var plugins by remember { mutableStateOf(emptyList<OrbitPluginConfig>()) }
    var selected by remember { mutableStateOf<OrbitPluginConfig?>(null) }
    var channel by remember(selected?.id) { mutableStateOf<OrbitChannel?>(null) }
    var content by remember { mutableStateOf(emptyList<OrbitVideoItem>()) }
    var next by remember { mutableStateOf<String?>(null) }
    var chapters by remember { mutableStateOf(emptyList<OrbitEpisodeItem>()) }
    var chaptersNext by remember { mutableStateOf<String?>(null) }
    var selectedVideo by remember { mutableStateOf<OrbitVideoItem?>(null) }
    var selectedEpisode by remember { mutableStateOf<OrbitEpisodeItem?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var pluginError by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var capabilitiesRetry by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var activeQuery by remember { mutableStateOf("") }
    var filterValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
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
                    selected = selected?.let { current -> result.firstOrNull { it.id == current.id } } ?: result.firstOrNull()
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
                if (generation == requestGeneration && selected?.id == config.id) {
                    content = result.items; next = result.next; listContext = context.copy(next = result.next)
                    if (result.items.isEmpty()) actionError = "此列表目前没有内容"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == requestGeneration) actionError = failure.message ?: "加载 Orbit 列表失败" }
            finally { if (generation == requestGeneration) loading = false }
        }
    }

    LaunchedEffect(selected?.id, capabilitiesRetry) {
        val config = selected ?: return@LaunchedEffect
        val generation = ++requestGeneration
        channel = null; content = emptyList(); chapters = emptyList(); selectedVideo = null
        next = null; activeQuery = ""; query = ""; actionError = null; loadingMore = false
        filterValues = emptyMap(); appliedFilters = emptyMap()
        loading = true
        try {
            val caps = withContext(Dispatchers.IO) { repository.capabilities(config) }
            if (generation == requestGeneration && selected?.id == config.id) {
                channel = caps
                // loadFirstPage is intentionally called after the capability state is committed.
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if (generation == requestGeneration) actionError = failure.message ?: "读取频道能力失败" }
        finally { if (generation == requestGeneration) loading = false }
    }

    LaunchedEffect(channel, selected?.id) { if (channel != null) loadFirstPage("") }

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
        selectedVideo = item; selectedEpisode = null; chapters = emptyList(); chaptersNext = null; chapterContext = context
        actionError = null; loading = true; loadingMore = false
        val generation = ++requestGeneration
        scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { repository.invoke(config, context) }
                val result = repository.listEpisodes(raw)
                if (generation == requestGeneration && selectedVideo?.id == item.id) {
                    chapters = result
                    chaptersNext = raw.optJSONObject("next")?.toString().takeIf { raw.optBoolean("hasMore", true) }
                    chapterContext = context.copy(next = chaptersNext)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == requestGeneration) actionError = failure.message ?: "读取选集失败" }
            finally { if (generation == requestGeneration) loading = false }
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
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == requestGeneration) actionError = failure.message ?: "加载章节下一页失败" }
            finally { if (generation == requestGeneration) loadingMore = false }
        }
    }

    fun playEpisode(episode: OrbitEpisodeItem) {
        if (loading) return
        val config = selected ?: return
        val video = selectedVideo ?: return
        val op = channel?.operations?.firstOrNull { it.operation == "chapterDetail" && it.executable }
        if (op == null) { actionError = "此频道未声明章节播放信息能力"; return }
        val parentKey = op.targets.keys.firstOrNull() ?: "itemId"
        val chapterKey = op.targets.keys.firstOrNull { it != parentKey } ?: run { actionError = "章节详情缺少章节 target 声明"; return }
        val context = baseContext(config, op, activeQuery, mapOf(parentKey to video.id, chapterKey to episode.id))
        selectedEpisode = episode
        loading = true; actionError = null
        val generation = ++requestGeneration
        scope.launch {
            try {
                val playback = withContext(Dispatchers.IO) { repository.playback(repository.invoke(config, context)) }
                if (generation == requestGeneration && selectedVideo?.id == video.id) {
                    actionError = null
                    onPlay(remoteVideo(video, config), PlaybackLine("Orbit", listOf(PlaybackEpisode(playback.title.ifBlank { episode.title }, playback.url))), PlaybackEpisode(playback.title.ifBlank { episode.title }, playback.url))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (generation == requestGeneration) actionError = failure.message ?: "无法播放此章节" }
            finally { if (generation == requestGeneration) loading = false }
        }
    }

    BackHandler(enabled = selectedVideo != null) { selectedVideo = null; selectedEpisode = null; chapters = emptyList(); chaptersNext = null; actionError = null }

    val tags = remember(content) { content.flatMap { item ->
        val values = item.raw.optJSONArray("tags") ?: return@flatMap emptyList()
        (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
    }.distinct() }

    Column(Modifier.fillMaxSize().background(OrbitInk).padding(start = 108.dp, end = 40.dp, top = 28.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Orbit 插件", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("经绑定的 Cineo Hub 安全浏览", color = OrbitMuted, fontSize = 13.sp)
            }
            OrbitAction("刷新", primary = true, modifier = Modifier.focusRequester(entryFocusRequester)) { retry++ }
        }
        Spacer(Modifier.height(18.dp))
        if (plugins.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                plugins.forEach { config ->
                    OrbitAction("${config.name} · ${config.channelId}", selected = config.id == selected?.id) {
                        if (config.id != selected?.id) { requestGeneration++; selected = config }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            pluginError != null -> OrbitMessage(pluginError!!, if (plugins.isEmpty()) "重试" else null) { retry++ }
            plugins.isEmpty() && loading -> OrbitMessage("正在读取 Hub 插件配置…")
            plugins.isEmpty() -> OrbitMessage("Hub 尚未绑定或没有启用 Orbit 插件。绑定账号并在 Hub 管理端启用插件后重试。", "重试") { retry++ }
            channel == null && loading -> OrbitMessage("正在读取 ${selected?.name.orEmpty()} 能力…")
            channel == null && actionError != null -> OrbitMessage(actionError!!, "重试") { capabilitiesRetry++ }
            selectedVideo != null -> {
                Text("${selectedVideo!!.title} · ${channel?.features?.optJSONObject("chapters")?.optString("label", "选集") ?: "选集"}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                when {
                    loading && chapters.isEmpty() -> OrbitMessage("正在读取选集…")
                    chapters.isEmpty() && actionError != null -> OrbitMessage(actionError!!, "重试") { selectedVideo?.let(::openVideo) }
                    chapters.isEmpty() -> OrbitMessage("此作品暂无可用选集")
                    else -> Column(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(chapters, key = { it.id }) { episode ->
                                OrbitAction(episode.title, primary = true, modifier = Modifier.fillMaxWidth(), enabled = !loading) { playEpisode(episode) }
                            }
                            if (chaptersNext != null) item {
                                OrbitAction(if (loadingMore) "正在加载章节…" else "加载更多选集", primary = true,
                                    modifier = Modifier.fillMaxWidth(), enabled = !loadingMore && !loading) { loadMoreChapters() }
                            }
                        }
                        if (actionError != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(actionError!!, color = OrbitGold, fontSize = 12.sp)
                            selectedEpisode?.let { failed -> OrbitAction("重试当前集") { playEpisode(failed) } }
                        }
                    }
                }
            }
            else -> {
                val canSearch = channel?.operations?.any { it.operation == "search" && it.executable } == true
                val featureSearch = channel?.features?.optJSONObject("search")
                if (canSearch) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BasicTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { activeQuery = query.trim(); keyboard?.hide(); loadFirstPage(activeQuery) }),
                            modifier = Modifier.weight(1f).background(Color(0xFF18212D), RoundedCornerShape(10.dp)).padding(14.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                            decorationBox = { inner -> if (query.isEmpty()) Text("搜索 ${featureSearch?.optString("param") ?: "内容"}", color = OrbitMuted, fontSize = 14.sp); inner() }
                        )
                        OrbitAction("搜索", primary = true) { activeQuery = query.trim(); keyboard?.hide(); loadFirstPage(activeQuery) }
                        if (activeQuery.isNotBlank()) OrbitAction("清除") { query = ""; activeQuery = ""; loadFirstPage("") }
                    }
                } else if (featureSearch != null) {
                    Text("能力声明不完整：频道标记了搜索，但没有可执行 search operation。", color = OrbitGold, fontSize = 13.sp)
                } else {
                    Text("此配置绑定的频道未声明搜索能力", color = OrbitMuted, fontSize = 13.sp)
                }
                val filterOperation = channel?.operations?.firstOrNull {
                    it.operation == if (activeQuery.isNotBlank() && canSearch) "search" else "feed"
                } ?: channel?.operations?.firstOrNull { it.operation !in setOf("search", "page", "chapters", "chapterDetail", "detail") }
                val paginationParam = filterOperation?.pagination?.param ?: "page"
                val filterFields = filterOperation?.let { (it.required + it.optional) - paginationParam - "query" }.orEmpty().toList().sorted()
                if (filterFields.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("频道筛选参数（按能力声明）", color = OrbitMuted, fontSize = 11.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        filterFields.forEach { field ->
                            BasicTextField(
                                value = filterValues[field] ?: channel?.defaults?.get(field).orEmpty(),
                                onValueChange = { value -> filterValues = filterValues + (field to value) }, singleLine = true,
                                modifier = Modifier.weight(1f).background(Color(0xFF18212D), RoundedCornerShape(8.dp)).padding(11.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                                decorationBox = { inner -> if ((filterValues[field] ?: channel?.defaults?.get(field)).isNullOrBlank()) Text(field, color = OrbitMuted, fontSize = 12.sp); inner() }
                            )
                        }
                        OrbitAction("应用", primary = true) {
                            appliedFilters = filterFields.associateWith { filterValues[it] ?: channel?.defaults?.get(it).orEmpty() }
                            activeQuery = query.trim()
                            loadFirstPage(activeQuery)
                        }
                    }
                }
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OrbitAction("全部", selected = selectedTag == null) { selectedTag = null; next = null; listContext = null }
                        tags.forEach { tag -> OrbitAction(tag, selected = selectedTag == tag) { selectedTag = tag; next = null; listContext = null } }
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
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(visible, key = { it.id }) { item ->
                                OrbitContentRow(item) { openVideo(item) }
                            }
                            if (next != null) item {
                                OrbitAction(if (loadingMore) "正在加载下一页…" else "加载更多", primary = true, modifier = Modifier.fillMaxWidth(), enabled = !loadingMore && !loading) { loadMore() }
                            }
                        }
                        if (actionError != null) Text(actionError!!, color = OrbitGold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun OrbitContentRow(item: OrbitVideoItem, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color(0xFF273545) else Color(0xFF151E29), RoundedCornerShape(12.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) OrbitGold else Color(0xFF293645), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).focusable().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (item.summary.isNotBlank()) Text(item.summary, color = OrbitMuted, fontSize = 12.sp, maxLines = 2)
        }
        Text("选集  ›", color = OrbitGold, fontSize = 13.sp)
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
        modifier.onFocusChanged { focused = it.isFocused }.focusable(enabled)
            .background(when { focused -> Color(0xFFE7EBE7); selected || primary -> Color(0xFF344354); else -> Color.Transparent }, shape)
            .border(if (focused || selected) 2.dp else 1.dp, if (focused) OrbitGold else Color(0xFF526171), shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (focused) Color(0xFF172026) else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

