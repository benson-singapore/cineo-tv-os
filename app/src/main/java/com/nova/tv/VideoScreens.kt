package com.nova.tv

import android.app.Activity
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import java.net.URL

private val ScreenInk = Color(0xFF090D13)
private val ScreenMuted = Color(0xFFB6C0CD)
private val AccentGold = Color(0xFFE9B44C)

@OptIn(ExperimentalFoundationApi::class)
private val MinimalFocusBringIntoViewSpec = object : BringIntoViewSpec {}

@Composable
fun VideoSearchScreen(
    repository: MacCmsRepository,
    entryFocusRequester: FocusRequester,
    menuFocusRequester: FocusRequester,
    onSelect: (RemoteVideo, FocusRequester) -> Unit,
    onOpenSources: () -> Unit
) {
    val sources = remember { repository.sources() }
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<SearchResult?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    fun search() {
        val value = query.trim()
        if (value.isEmpty()) { error = "输入片名或关键词开始搜索"; return }
        if (sources.none { it.enabled }) { error = "请先添加并启用一个视频源"; return }
        loading = true
        error = null
        result = null
        keyboard?.hide()
        scope.launch {
            try {
                result = repository.search(value)
                if (result?.videos.isNullOrEmpty() && result?.errors.isNullOrEmpty()) error = "没有找到相关影片"
            } catch (failure: Exception) {
                error = failure.message ?: "搜索失败，请检查网络和视频源"
            } finally { loading = false }
        }
    }

    LaunchedEffect(Unit) { runCatching { entryFocusRequester.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(start = 108.dp, end = 38.dp, top = 28.dp, bottom = 24.dp)) {
        Text("搜索影片", color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        Text("在已启用的 MacCMS 视频源中查找电影、剧集和纪录片", color = ScreenMuted, fontSize = 13.sp)
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InputBox(
                label = "片名 / 关键词",
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                inputFocusRequester = entryFocusRequester,
                onSubmit = { search() }
            )
            TvActionButton("搜索", primary = true, onClick = { search() })
            if (sources.isEmpty()) TvActionButton("配置视频源", onClick = onOpenSources)
        }
        Spacer(Modifier.height(16.dp))
        val sourceLabel = if (sources.isEmpty()) "尚未配置视频源" else "搜索范围：${sources.count { it.enabled }} 个已启用站点"
        Text(sourceLabel, color = AccentGold, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("···", color = AccentGold, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    Text("正在查询视频站点…", color = ScreenMuted, fontSize = 14.sp)
                }
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error.orEmpty(), color = ScreenMuted, fontSize = 16.sp)
            }
            result != null -> {
                val items = result!!.videos
                Text("找到 ${items.size} 个结果", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (result!!.errors.isNotEmpty()) {
                    Spacer(Modifier.height(5.dp))
                    Text("部分站点暂不可用：${result!!.errors.joinToString("；")}", color = Color(0xFFD5A069), fontSize = 11.sp, maxLines = 2)
                }
                Spacer(Modifier.height(12.dp))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val gridColumns = ((maxWidth + 14.dp) / (175.dp + 14.dp)).toInt().coerceAtLeast(1)
                    LazyVerticalGrid(
                        modifier = Modifier.fillMaxSize(),
                        columns = GridCells.Adaptive(175.dp),
                        contentPadding = PaddingValues(bottom = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        itemsIndexed(items, key = { _, video -> video.id }) { index, video ->
                            val videoFocusRequester = remember(video.id) { FocusRequester() }
                            VideoResultCard(
                                video = video,
                                onClick = { onSelect(video, videoFocusRequester) },
                                modifier = Modifier.focusRequester(videoFocusRequester)
                                    .focusProperties {
                                        if (index % gridColumns == 0) left = menuFocusRequester
                                    }
                            )
                        }
                    }
                }
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (sources.isEmpty()) "先配置资源站，再开始查找内容" else "输入片名，搜索所有启用的视频源", color = ScreenMuted, fontSize = 16.sp)
                    if (sources.isEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        TvActionButton("打开视频源配置", primary = true, onClick = onOpenSources)
                    }
                }
            }
        }
    }
}

@Composable
fun VideoCatalogScreen(
    repository: MacCmsRepository,
    section: String,
    entryFocusRequester: FocusRequester,
    menuFocusRequester: FocusRequester,
    onSelect: (RemoteVideo, FocusRequester) -> Unit,
    onOpenConfig: () -> Unit
) {
    val context = LocalContext.current
    val displayDatabase = remember(context) { CineoDatabase(context.applicationContext) }
    val source = remember { repository.defaultSource() }
    var categories by remember(section, source?.id) { mutableStateOf(emptyList<VideoCategory>()) }
    var categoriesLoaded by remember(section, source?.id) { mutableStateOf(false) }
    var selectedCategoryId by remember(section) { mutableStateOf<String?>(null) }
    var videos by remember(section, source?.id) { mutableStateOf(emptyList<RemoteVideo>()) }
    var page by remember(section, source?.id) { mutableIntStateOf(1) }
    var hasMore by remember(section, source?.id) { mutableStateOf(false) }
    var loading by remember(section, source?.id) { mutableStateOf(source != null) }
    var loadingMore by remember { mutableStateOf(false) }
    var paginationError by remember { mutableStateOf<String?>(null) }
    var error by remember(section, source?.id) { mutableStateOf<String?>(null) }
    var reloadKey by remember(section, source?.id) { mutableIntStateOf(0) }
    var showPreviewConfig by remember { mutableStateOf(false) }
    var columns by remember {
        mutableIntStateOf(displayDatabase.readAppSetting(CATALOG_COLUMNS_KEY)?.toIntOrNull()?.coerceIn(2, 6) ?: 4)
    }
    var posterRatioKey by remember { mutableStateOf(displayDatabase.readAppSetting(CATALOG_POSTER_RATIO_KEY) ?: "3:4") }
    val posterRatios = listOf("3:4", "4:3", "16:9", "9:16", "1:1")
    val posterRatio = when (posterRatioKey) {
        "4:3" -> 4f / 3f
        "16:9" -> 16f / 9f
        "9:16" -> 9f / 16f
        "1:1" -> 1f
        else -> 3f / 4f
    }
    val gridState = rememberLazyGridState()
    val title = when (section) {
        "首页" -> "精选内容"
        "剧集" -> "剧集"
        "纪录片" -> "纪录片"
        "动漫" -> "动漫"
        "短剧" -> "短剧"
        else -> "电影"
    }

    LaunchedEffect(section, source?.id, selectedCategoryId, reloadKey) {
        val activeSource = source ?: return@LaunchedEffect
        loading = true
        loadingMore = false
        paginationError = null
        error = null
        videos = emptyList()
        page = 1
        hasMore = false
        try {
            if (section != "首页" && !categoriesLoaded) {
                val available = runCatching { repository.categories(activeSource.id) }.getOrDefault(emptyList())
                categories = catalogCategories(section, available)
                categoriesLoaded = true
                val preferred = preferredCatalogCategory(section, categories)
                if (selectedCategoryId == null && preferred != null) {
                    selectedCategoryId = preferred.id
                    return@LaunchedEffect
                }
            }
            val firstPage = repository.browse(activeSource.id, selectedCategoryId, page = 1)
            videos = firstPage.videos
            // Reset after the new category arrives, before showing its grid.
            gridState.scrollToItem(0)
            page = firstPage.page
            hasMore = firstPage.hasMore
            if (firstPage.videos.isEmpty()) error = "这个视频源暂时没有返回内容"
        } catch (failure: Exception) {
            error = failure.message ?: "无法读取视频站点内容"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(gridState, section, source?.id, selectedCategoryId, columns) {
        if (source == null) return@LaunchedEffect
        snapshotFlow {
            val layout = gridState.layoutInfo
            val lastVisibleIndex = layout.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            lastVisibleIndex to layout.totalItemsCount
        }.distinctUntilChanged().collect { nearEnd ->
            val (lastVisibleIndex, totalItemsCount) = nearEnd
            val isNearEnd = totalItemsCount > 0 && lastVisibleIndex >= totalItemsCount - columns
            if (!isNearEnd) {
                paginationError = null
            } else if (hasMore && !loading && !loadingMore && paginationError == null) {
                loadingMore = true
                val nextPage = page + 1
                try {
                    val next = repository.browse(source.id, selectedCategoryId, page = nextPage)
                    videos = (videos + next.videos).distinctBy { it.id }
                    page = next.page
                    hasMore = next.hasMore && next.videos.isNotEmpty()
                } catch (failure: Exception) {
                    paginationError = failure.message ?: "加载下一页失败，滚动离开后可重试"
                } finally {
                    loadingMore = false
                }
            }
        }
    }

    fun saveDisplayPreference(key: String, value: String) {
        displayDatabase.writeAppSettings(mapOf(key to value))
    }

    Column(Modifier.fillMaxSize().padding(start = 108.dp, end = 38.dp, top = 26.dp, bottom = 22.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(
                Modifier.weight(1f).padding(end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(title, color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        source == null -> "还没有启用的视频源"
                        source.isDefault -> "当前默认站点：${source.name}"
                        else -> "当前站点：${source.name}"
                    },
                    color = ScreenMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            TvActionButton("预览配置", onClick = { showPreviewConfig = true })
        }
        if (section != "首页" && categories.isNotEmpty()) {
            Spacer(Modifier.height(17.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { TvActionButton("全部", selected = selectedCategoryId == null) { selectedCategoryId = null } }
                items(categories, key = { it.id }) { category ->
                    TvActionButton(category.name, selected = selectedCategoryId == category.id) {
                        selectedCategoryId = category.id
                    }
                }
            }
        }
        Spacer(Modifier.height(15.dp))
        when {
            source == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("导入默认站点或添加视频源后，这里会显示站点内容", color = ScreenMuted, fontSize = 15.sp)
                    Spacer(Modifier.height(16.dp))
                    TvActionButton("前往配置", primary = true, onClick = onOpenConfig)
                }
            }
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("···", color = AccentGold, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text("正在从 ${source.name} 拉取$title…", color = ScreenMuted, fontSize = 14.sp)
                }
            }
            error != null && videos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error.orEmpty(), color = ScreenMuted, fontSize = 15.sp)
                    Spacer(Modifier.height(14.dp))
                    TvActionButton("重新加载", primary = true) { reloadKey++ }
                }
            }
            videos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无内容", color = ScreenMuted, fontSize = 15.sp)
            }
            else -> {
                Text("${videos.size} 部 · 来自 ${source.name}", color = AccentGold, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                LazyVerticalGrid(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(videos, key = { _, video -> video.id }) { index, video ->
                        val fallbackFocusRequester = remember(video.id) { FocusRequester() }
                        val videoFocusRequester = if (index == 0) entryFocusRequester else fallbackFocusRequester
                        VideoResultCard(
                            video = video,
                            onClick = { onSelect(video, videoFocusRequester) },
                            posterAspectRatio = posterRatio,
                            modifier = Modifier.focusRequester(videoFocusRequester)
                                .focusProperties {
                                    if (index % columns == 0) left = menuFocusRequester
                                }
                        )
                    }
                    if (loadingMore || paginationError != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    if (loadingMore) "正在加载下一页…" else paginationError.orEmpty(),
                                    color = ScreenMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPreviewConfig) {
        val columnFocus = remember { (2..6).associateWith { FocusRequester() } }
        val ratioFocus = remember { posterRatios.associateWith { FocusRequester() } }
        val doneFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            columnFocus.getValue(columns).requestFocus()
        }
        Dialog(
            onDismissRequest = { showPreviewConfig = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                Modifier.width(620.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF18212D)).padding(26.dp)
            ) {
                Text("预览配置", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                Text("更改当前列表每行列数和封面比例", color = ScreenMuted, fontSize = 12.sp)
                Spacer(Modifier.height(24.dp))
                Text("每行展示", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (2..6).forEach { count ->
                        TvActionButton(
                            "${count} 列",
                            selected = columns == count,
                            modifier = Modifier.focusRequester(columnFocus.getValue(count)).focusProperties {
                                down = ratioFocus.getValue(posterRatioKey)
                            },
                            onClick = {
                                columns = count
                                saveDisplayPreference(CATALOG_COLUMNS_KEY, count.toString())
                            }
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text("封面比例", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    posterRatios.forEach { ratio ->
                        TvActionButton(
                            ratio,
                            selected = posterRatioKey == ratio,
                            modifier = Modifier.focusRequester(ratioFocus.getValue(ratio)).focusProperties {
                                up = columnFocus.getValue(columns)
                                down = doneFocus
                            },
                            onClick = {
                                posterRatioKey = ratio
                                saveDisplayPreference(CATALOG_POSTER_RATIO_KEY, ratio)
                            }
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TvActionButton(
                        "完成", primary = true,
                        modifier = Modifier.focusRequester(doneFocus).focusProperties {
                            up = ratioFocus.getValue(posterRatioKey)
                        },
                        onClick = { showPreviewConfig = false }
                    )
                }
            }
        }
    }
}

private const val CATALOG_COLUMNS_KEY = "catalog.columns"
private const val CATALOG_POSTER_RATIO_KEY = "catalog.poster_ratio"

private fun catalogCategories(section: String, categories: List<VideoCategory>): List<VideoCategory> {
    val byId = categories.associateBy { it.id }
    val parentIds = categories.mapNotNull { it.parentId }.toSet()
    fun pathNames(category: VideoCategory): List<String> {
        val names = mutableListOf(category.name)
        var parentId = category.parentId
        val seen = mutableSetOf(category.id)
        while (parentId != null && seen.add(parentId)) {
            val parent = byId[parentId] ?: break
            names += parent.name
            parentId = parent.parentId
        }
        return names
    }
    fun matchesPath(names: List<String>): Boolean = names.any { name -> when (section) {
        "电影" -> name.contains("电影") || name.contains("影片") ||
            (name.endsWith("片") && !name.contains("动漫") && !name.contains("纪录") && !name.contains("综艺"))
        "剧集" -> listOf("电视剧", "剧集", "连续剧", "短剧", "台剧", "韩剧", "美剧", "日剧", "series", "tv")
            .any { name.contains(it, ignoreCase = true) } &&
            !name.contains("片") && !name.contains("动漫") && !name.contains("动画") && !name.contains("综艺")
        "纪录片" -> name.contains("纪录") || name.contains("纪实")
        "动漫" -> listOf("动漫", "动画", "番剧", "anime").any { name.contains(it, ignoreCase = true) }
        "短剧" -> name.contains("短剧")
        else -> false
    } }
    return categories.filter { it.id !in parentIds && matchesPath(pathNames(it)) }
}

private fun preferredCatalogCategory(section: String, categories: List<VideoCategory>): VideoCategory? {
    val names = when (section) {
        "电影" -> listOf("电影片", "电影")
        "剧集" -> listOf("连续剧", "电视剧", "剧集")
        "纪录片" -> listOf("纪录片", "纪录")
        "动漫" -> listOf("动漫", "动漫片", "动画", "番剧")
        "短剧" -> listOf("短剧")
        else -> emptyList()
    }
    return names.firstNotNullOfOrNull { target ->
        categories.firstOrNull { it.name.equals(target, ignoreCase = true) }
    } ?: categories.firstOrNull()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoHomeScreen(
    repository: MacCmsRepository,
    entryFocusRequester: FocusRequester,
    menuFocusRequester: FocusRequester,
    preserveHeroPositionOnMenuEntry: MutableState<Boolean>,
    onContentFocused: (FocusRequester) -> Unit = {},
    dataRevision: Int,
    onOpenDetail: (RemoteVideo, FocusRequester) -> Unit,
    onResume: (WatchRecord) -> Unit,
    onOpenList: (String) -> Unit,
    onOpenSection: (String) -> Unit,
    onOpenConfig: () -> Unit
) {
    val source = remember { repository.defaultSource() }
    var rows by remember(source?.id) { mutableStateOf<Map<String, List<RemoteVideo>>>(emptyMap()) }
    var loading by remember(source?.id) { mutableStateOf(source != null) }
    var error by remember(source?.id) { mutableStateOf<String?>(null) }
    var refreshKey by remember(source?.id) { mutableIntStateOf(0) }
    var watchRecords by remember { mutableStateOf(repository.watchHistory()) }
    var favorites by remember { mutableStateOf(repository.favorites()) }
    val sections = listOf("电影", "剧集", "动漫", "短剧")

    LaunchedEffect(dataRevision) {
        watchRecords = repository.watchHistory()
        favorites = repository.favorites()
    }

    LaunchedEffect(source?.id, refreshKey) {
        val activeSource = source ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            val categories = repository.categories(activeSource.id)
            val selected = sections.mapNotNull { section ->
                preferredCatalogCategory(section, catalogCategories(section, categories))?.let { section to it.id }
            }
            val loaded = coroutineScope {
                selected.map { (section, categoryId) -> async {
                    section to runCatching { repository.browse(activeSource.id, categoryId, 1).videos }
                        .getOrElse { emptyList() }
                } }.awaitAll()
            }
            rows = loaded.toMap()
            if (rows.values.all { it.isEmpty() }) error = "默认视频源暂时没有返回内容"
        } catch (failure: Exception) {
            error = failure.message ?: "无法读取首页内容"
        } finally { loading = false }
    }

    val continueRecords = watchRecords.filter { it.positionMs > 0 && !it.isComplete }
    val latest = watchRecords.firstOrNull()
    val featured = latest?.video ?: sections.firstNotNullOfOrNull { rows[it]?.firstOrNull() }

    if (source == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("配置默认视频源后，首页会自动展示站点内容", color = ScreenMuted, fontSize = 15.sp)
                Spacer(Modifier.height(16.dp))
                TvActionButton("前往配置", primary = true, modifier = Modifier.focusRequester(entryFocusRequester), onClick = onOpenConfig)
            }
        }
        return
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val platformBringIntoViewSpec = LocalBringIntoViewSpec.current
    val homeBringIntoViewSpec = remember(platformBringIntoViewSpec, preserveHeroPositionOnMenuEntry) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                if (preserveHeroPositionOnMenuEntry.value) {
                    MinimalFocusBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
                } else {
                    platformBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
                }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides homeBringIntoViewSpec) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionUp || event.key == Key.DirectionDown)
                ) {
                    preserveHeroPositionOnMenuEntry.value = false
                }
                false
            },
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                if (featured != null) {
                    RemoteHomeHero(
                        video = featured,
                        entryFocusRequester = entryFocusRequester,
                        menuFocusRequester = menuFocusRequester,
                        eyebrow = if (latest != null) "最近观看" else "",
                        primaryLabel = when {
                            latest == null -> "查看详情"
                            latest.isComplete -> "重新播放"
                            else -> "继续观看"
                        },
                        onHeroActionFocus = {
                            val needsScrollToTop = listState.firstVisibleItemIndex != 0 ||
                                listState.firstVisibleItemScrollOffset != 0
                            if (!preserveHeroPositionOnMenuEntry.value || needsScrollToTop) {
                                preserveHeroPositionOnMenuEntry.value = true
                            }
                            if (needsScrollToTop) {
                                scope.launch { listState.scrollToItem(0) }
                            }
                        },
                        onHeroActionUp = {
                            val needsScrollToTop = listState.firstVisibleItemIndex != 0 ||
                                listState.firstVisibleItemScrollOffset != 0
                            if (needsScrollToTop) {
                                preserveHeroPositionOnMenuEntry.value = true
                                scope.launch { listState.scrollToItem(0) }
                                true
                            } else false
                        },
                        onPrimary = { if (latest != null) onResume(latest) else onOpenDetail(featured, entryFocusRequester) },
                        onSecondary = { video, requester -> onOpenDetail(video, requester) }
                    )
                } else if (loading) {
                    Box(
                        Modifier.fillMaxWidth().height(400.dp).focusRequester(entryFocusRequester).focusable(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("正在从 ${source.name} 获取首页内容…", color = ScreenMuted, fontSize = 15.sp)
                    }
                } else if (error != null) {
                    Column(
                        Modifier.fillMaxWidth().height(400.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(error.orEmpty(), color = ScreenMuted, fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                        TvActionButton("重新加载", modifier = Modifier.focusRequester(entryFocusRequester)) { refreshKey++ }
                    }
                }
            }
            if (continueRecords.isNotEmpty()) item {
                Box(Modifier.fillMaxWidth().padding(start = 108.dp, end = 36.dp)) {
                    HomeContentRail(
                        title = "继续观看",
                        videos = continueRecords.map { it.video },
                        progressById = continueRecords.associate { it.video.id to it.progress },
                        onMore = { onOpenList("继续观看") },
                        onSelect = { video, _ -> continueRecords.firstOrNull { it.video.id == video.id }?.let(onResume) },
                        menuFocusRequester = menuFocusRequester,
                        onContentFocused = onContentFocused,
                        bringIntoViewSpec = platformBringIntoViewSpec
                    )
                }
            }
            if (favorites.isNotEmpty()) item {
                Box(Modifier.fillMaxWidth().padding(start = 108.dp, end = 36.dp)) {
                    HomeContentRail(
                        title = "我的收藏",
                        videos = favorites,
                        onMore = { onOpenList("我的收藏") },
                        onSelect = onOpenDetail,
                        menuFocusRequester = menuFocusRequester,
                        onContentFocused = onContentFocused,
                        bringIntoViewSpec = platformBringIntoViewSpec
                    )
                }
            }
            sections.forEach { section ->
                val videos = rows[section].orEmpty()
                if (videos.isNotEmpty()) item {
                    Box(Modifier.fillMaxWidth().padding(start = 108.dp, end = 36.dp)) {
                        HomeContentRail(
                            title = section,
                            videos = videos,
                            onMore = { onOpenSection(section) },
                            onSelect = onOpenDetail,
                            menuFocusRequester = menuFocusRequester,
                            onContentFocused = onContentFocused,
                            bringIntoViewSpec = platformBringIntoViewSpec
                        )
                    }
                }
            }
            if (loading) item {
                Text("正在加载分类内容…", Modifier.padding(start = 108.dp, top = 10.dp), color = ScreenMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun RemoteHomeHero(
    video: RemoteVideo,
    entryFocusRequester: FocusRequester,
    menuFocusRequester: FocusRequester,
    eyebrow: String,
    primaryLabel: String,
    onHeroActionFocus: () -> Unit,
    onHeroActionUp: () -> Boolean,
    onPrimary: () -> Unit,
    onSecondary: (RemoteVideo, FocusRequester) -> Unit
) {
    var artwork by remember(video.posterUrl) { mutableStateOf<LoadedArtwork?>(null) }
    val secondaryFocusRequester = remember(video.id) { FocusRequester() }
    LaunchedEffect(video.posterUrl) { artwork = loadArtwork(video.posterUrl) }
    Box(Modifier.fillMaxWidth().height(420.dp).clipToBounds()) {
        val loadedArtwork = artwork
        val portraitArtwork = loadedArtwork?.let { it.image.height > it.image.width } == true
        if (loadedArtwork != null) {
            Image(
                loadedArtwork.image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = 1.16f; scaleY = 1.22f }
                    .blur(42.dp),
                contentScale = ContentScale.Crop
            )
            Box(Modifier.fillMaxSize().background(loadedArtwork.dominantColor.copy(alpha = .20f)))
            Image(
                loadedArtwork.image,
                contentDescription = video.title,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = if (portraitArtwork) .72f else .58f
                },
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF334354), Color(0xFF151B27)))),
                contentAlignment = Alignment.Center
            ) {
                Text(video.title.take(1), color = Color.White.copy(alpha = .55f), fontSize = 54.sp, fontWeight = FontWeight.Bold)
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(colorStops = arrayOf(
            0f to ScreenInk.copy(alpha = .96f),
            .34f to ScreenInk.copy(alpha = .82f),
            .56f to ScreenInk.copy(alpha = .44f),
            .76f to Color.Transparent,
            1f to Color.Transparent
        ))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(colorStops = arrayOf(
            0f to ScreenInk.copy(alpha = .22f),
            .52f to Color.Transparent,
            .73f to ScreenInk.copy(alpha = .30f),
            .90f to ScreenInk.copy(alpha = .82f),
            1f to ScreenInk
        ))))
        Column(
            Modifier.align(Alignment.CenterStart)
                .padding(start = 140.dp, end = 40.dp)
                .widthIn(max = 590.dp)
        ) {
            if (eyebrow.isNotBlank()) {
                Text(eyebrow, color = AccentGold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
            }
            Text(video.title, color = Color.White, fontSize = 33.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Spacer(Modifier.height(9.dp))
            Text(listOf(video.year, video.category, video.sourceName).filter(String::isNotBlank).joinToString("　·　"), color = Color.White.copy(alpha = .86f), fontSize = 12.sp)
            Spacer(Modifier.height(9.dp))
            Text(video.description.ifBlank { "从喜欢的故事开始观看。" }, color = ScreenMuted, fontSize = 12.sp, lineHeight = 19.sp, maxLines = 2)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvActionButton(
                    primaryLabel,
                    primary = true,
                    modifier = Modifier.focusRequester(entryFocusRequester)
                        .onFocusChanged { if (it.isFocused) onHeroActionFocus() }
                        .onKeyEvent { event ->
                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && onHeroActionUp()
                        }
                        .focusProperties { left = menuFocusRequester },
                    onClick = onPrimary
                )
                TvActionButton(
                    "影片详情",
                    modifier = Modifier.focusRequester(secondaryFocusRequester)
                        .onFocusChanged { if (it.isFocused) onHeroActionFocus() }
                        .onKeyEvent { event ->
                            event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && onHeroActionUp()
                        },
                    onClick = { onSecondary(video, secondaryFocusRequester) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeContentRail(
    title: String,
    videos: List<RemoteVideo>,
    onMore: () -> Unit,
    onSelect: (RemoteVideo, FocusRequester) -> Unit,
    menuFocusRequester: FocusRequester,
    bringIntoViewSpec: BringIntoViewSpec,
    onContentFocused: (FocusRequester) -> Unit = {},
    progressById: Map<String, Float> = emptyMap()
) {
    val moreFocusRequester = remember(title) { FocusRequester() }
    var lastFocusedVideoRequester by remember(title) { mutableStateOf<FocusRequester?>(null) }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TvActionButton(
                "查看更多  ›",
                modifier = Modifier.focusRequester(moreFocusRequester)
                    .onFocusChanged { if (it.isFocused) onContentFocused(moreFocusRequester) }
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            runCatching { lastFocusedVideoRequester?.requestFocus() == true }.getOrDefault(false)
                        } else false
                    },
                onClick = onMore
            )
        }
        Spacer(Modifier.height(10.dp))
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoViewSpec) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 12.dp)) {
                itemsIndexed(videos, key = { _, video -> video.id }) { index, video ->
                    val videoFocusRequester = remember(video.id) { FocusRequester() }
                    VideoResultCard(
                        video = video,
                        onClick = { onSelect(video, videoFocusRequester) },
                        progress = progressById[video.id],
                        onFocused = {
                            lastFocusedVideoRequester = videoFocusRequester
                            onContentFocused(videoFocusRequester)
                        },
                        modifier = Modifier.width(176.dp)
                            .focusRequester(videoFocusRequester)
                            .focusProperties {
                                if (index == 0) left = menuFocusRequester
                            }
                            .focusProperties { up = moreFocusRequester }
                            .onKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                    moreFocusRequester.requestFocus()
                                    true
                                } else false
                            }
                    )
                }
            }
        }
    }
}

@Composable
fun LocalVideoListScreen(
    repository: MacCmsRepository,
    section: String,
    dataRevision: Int,
    entryFocusRequester: FocusRequester,
    menuFocusRequester: FocusRequester,
    onOpenDetail: (RemoteVideo, FocusRequester) -> Unit,
    onResume: (WatchRecord) -> Unit
) {
    var records by remember { mutableStateOf(repository.watchHistory()) }
    var favorites by remember { mutableStateOf(repository.favorites()) }
    LaunchedEffect(dataRevision) {
        records = repository.watchHistory()
        favorites = repository.favorites()
    }
    val isContinue = section == "继续观看"
    val continued = records.filter { it.positionMs > 0 && !it.isComplete }
    val videos = if (isContinue) continued.map { it.video } else favorites
    val recordsById = continued.associateBy { it.video.id }
    Column(Modifier.fillMaxSize().padding(start = 108.dp, end = 38.dp, top = 28.dp, bottom = 22.dp)) {
        Text(section, color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("${videos.size} 部影片", color = ScreenMuted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        if (videos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (isContinue) "还没有继续观看的影片" else "还没有收藏影片", color = ScreenMuted, fontSize = 15.sp)
            }
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val gridColumns = ((maxWidth + 14.dp) / (175.dp + 14.dp)).toInt().coerceAtLeast(1)
                LazyVerticalGrid(
                    modifier = Modifier.fillMaxSize(),
                    columns = GridCells.Adaptive(175.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(videos, key = { _, video -> video.id }) { index, video ->
                        val fallbackFocusRequester = remember(video.id) { FocusRequester() }
                        val videoFocusRequester = if (index == 0) entryFocusRequester else fallbackFocusRequester
                        VideoResultCard(
                            video = video,
                            onClick = {
                                val record = recordsById[video.id]
                                if (isContinue && record != null) onResume(record) else onOpenDetail(video, videoFocusRequester)
                            },
                            progress = recordsById[video.id]?.progress,
                            modifier = Modifier.focusRequester(videoFocusRequester)
                                .focusProperties {
                                    if (index % gridColumns == 0) left = menuFocusRequester
                                }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    repository: MacCmsRepository,
    tmdbSettings: TmdbSettingsStore,
    tmdbRepository: TmdbMetadataRepository,
    menuSettings: NavigationMenuSettings,
    entryFocusRequester: FocusRequester,
    onMenuSettingsChange: (NavigationMenuSettings) -> Unit
) {
    val context = LocalContext.current
    val packageInfo = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        }.getOrNull()
    }
    val versionName = packageInfo?.versionName?.takeIf { it.isNotBlank() } ?: "未知"
    val versionCode = packageInfo?.longVersionCode?.toString() ?: "未知"
    var selectedSetting by remember { mutableStateOf("视频源") }
    var sources by remember { mutableStateOf(repository.sources()) }
    var editing by remember { mutableStateOf<VideoSource?>(null) }
    var addDialog by remember { mutableStateOf(false) }
    var importDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingTest by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val detailEntryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(entryFocusRequester) {
        entryFocusRequester.requestFocus()
    }

    fun loadBundledSources() {
        val count = repository.importDefaultSources()
        sources = repository.sources()
        message = "已导入 $count 个内置站点，默认站点为如意资源。站点地址来自 Flutter 版默认配置。"
    }

    Row(
        Modifier.fillMaxSize().padding(start = 108.dp, end = 40.dp, top = 26.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Column(Modifier.width(225.dp).fillMaxHeight()) {
            Text("配置", color = Color.White, fontSize = 31.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("设置分类", color = ScreenMuted, fontSize = 13.sp)
            Spacer(Modifier.height(26.dp))
            SettingsMenuItem(
                "视频源",
                selected = selectedSetting == "视频源",
                modifier = Modifier.focusRequester(entryFocusRequester)
                    .focusProperties { right = detailEntryFocusRequester }
            ) {
                selectedSetting = "视频源"
            }
            Spacer(Modifier.height(8.dp))
            SettingsMenuItem(
                "TMDB 数据",
                selected = selectedSetting == "TMDB 数据",
                modifier = Modifier.focusProperties { right = detailEntryFocusRequester }
            ) {
                selectedSetting = "TMDB 数据"
            }
            Spacer(Modifier.height(8.dp))
            SettingsMenuItem(
                "菜单配置",
                selected = selectedSetting == "菜单配置",
                modifier = Modifier.focusProperties { right = detailEntryFocusRequester }
            ) {
                selectedSetting = "菜单配置"
            }
            Spacer(Modifier.height(8.dp))
            SettingsMenuItem(
                "版本更新",
                selected = selectedSetting == "版本更新",
                modifier = Modifier.focusProperties { right = detailEntryFocusRequester }
            ) {
                selectedSetting = "版本更新"
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (selectedSetting == "菜单配置") {
                MenuConfigurationPage(
                    settings = menuSettings,
                    entryFocusRequester = detailEntryFocusRequester,
                    onSettingsChange = onMenuSettingsChange
                )
            } else if (selectedSetting == "版本更新") {
                AppVersionPage(versionName, versionCode, detailEntryFocusRequester)
            } else if (selectedSetting == "TMDB 数据") {
                TmdbSettingsPage(tmdbSettings, tmdbRepository, detailEntryFocusRequester)
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("视频源", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("管理视频站点与数据接口", color = ScreenMuted, fontSize = 13.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvActionButton(
                            "导入默认站点",
                            modifier = Modifier.focusRequester(detailEntryFocusRequester),
                            onClick = { loadBundledSources() }
                        )
                        TvActionButton("导入 JSON", onClick = { importDialog = true })
                        TvActionButton("＋ 添加站点", primary = true, onClick = { editing = null; addDialog = true })
                    }
                }
                Spacer(Modifier.height(20.dp))
                if (message != null) {
                    Text(message.orEmpty(), color = AccentGold, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                }
                if (sources.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("还没有视频源", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text("导入 Flutter 版内置资源站，或添加 / 导入 MacCMS API", color = ScreenMuted, fontSize = 13.sp)
                            Spacer(Modifier.height(18.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TvActionButton(
                                    "导入默认站点",
                                    primary = true,
                                    onClick = { loadBundledSources() }
                                )
                                TvActionButton("添加站点", primary = true, onClick = { editing = null; addDialog = true })
                                TvActionButton("导入 JSON", onClick = { importDialog = true })
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(sources, key = { it.id }) { source ->
                            SourceRow(
                                source = source,
                                testing = pendingTest == source.id,
                                onEdit = { editing = source; addDialog = true },
                                onToggle = { enabled -> repository.setEnabled(source, enabled); sources = repository.sources() },
                                onDefault = { repository.setDefault(source); sources = repository.sources(); message = "已将 ${source.name} 设为默认视频源" },
                                onTest = {
                                    pendingTest = source.id
                                    message = null
                                    scope.launch {
                                        val outcome = repository.testSource(source)
                                        pendingTest = null
                                        message = outcome.fold(
                                            { latency ->
                                                repository.saveSource(source.copy(lastLatencyMs = latency, lastError = null))
                                                sources = repository.sources()
                                                "${source.name} 连通性正常 · ${latency} ms"
                                            },
                                            { failure -> "${source.name} 测试失败：${failure.message ?: "请求失败"}" }
                                        )
                                    }
                                },
                                onDelete = { repository.deleteSource(source); sources = repository.sources(); message = "已删除 ${source.name}" }
                            )
                        }
                    }
                }
            }
        }
    }

    if (addDialog) SourceEditorDialog(
        initial = editing,
        onDismiss = { addDialog = false },
        onSave = { name, api, detail ->
            val existing = editing
            val source = VideoSource(
                id = existing?.id ?: "source-${System.currentTimeMillis()}",
                name = name.trim(), api = api.trim(), detailUrl = detail.trim(),
                enabled = existing?.enabled ?: true, isAdult = existing?.isAdult ?: false,
                isDefault = existing?.isDefault ?: sources.none { it.isDefault }
            )
            repository.saveSource(source)
            sources = repository.sources()
            addDialog = false
            message = "已保存 ${source.name}"
        }
    )
    if (importDialog) ImportSourcesDialog(
        onDismiss = { importDialog = false },
        onImport = { raw ->
            val result = repository.importConfig(raw)
            if (result.error != null) message = result.error
            else {
                sources = repository.sources()
                message = "已导入 ${result.imported} 个站点${if (result.skipped > 0) "，跳过 ${result.skipped} 个无效配置" else ""}"
            }
            importDialog = false
        }
    )
}

@Composable
private fun AppVersionPage(
    versionName: String,
    versionCode: String,
    entryFocusRequester: FocusRequester
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)

    Column(Modifier.fillMaxSize().padding(end = 8.dp)) {
        Text("版本更新", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        Text("查看当前应用版本与更新信息", color = ScreenMuted, fontSize = 13.sp)
        Spacer(Modifier.height(22.dp))
        Column(
            Modifier.fillMaxWidth()
                .focusRequester(entryFocusRequester)
                .onFocusChanged { focused = it.isFocused }
                .clip(shape)
                .background(Color(0xFF151D28))
                .border( if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
                .focusable()
                .padding(22.dp)
        ) {
            Text("当前版本", color = ScreenMuted, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Text("v$versionName", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("构建号 $versionCode", color = ScreenMuted, fontSize = 14.sp)
        }
        Spacer(Modifier.height(18.dp))
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Color(0xFF151D28)).padding(22.dp)
        ) {
            Text("应用更新", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text("尚未接入更新服务", color = AccentGold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text("后续可在这里查看新版说明、同步最新版本并下载安装包。", color = ScreenMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun TmdbSettingsPage(
    settings: TmdbSettingsStore,
    repository: TmdbMetadataRepository,
    entryFocusRequester: FocusRequester
) {
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(settings.isConfigured()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var cacheRetentionDays by remember(settings) { mutableIntStateOf(settings.cacheRetentionDays) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(end = 8.dp)) {
        Text("TMDB 数据服务", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        Text(
            "配置 TMDB API Read Access Token，为剧集详情补充横版封面、评分、简介、主创和分集图片。",
            color = ScreenMuted,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(22.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF151D28)).padding(22.dp)
        ) {
            Text("访问凭证", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                if (configured) "已配置 · Token 使用 Android Keystore 加密保存，不会回显。输入新 Token 可替换现有配置。"
                else "尚未配置。保存前会先连接 TMDB 验证 Token。",
                color = if (configured) AccentGold else ScreenMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InputBox(
                    label = "TMDB API Read Access Token",
                    value = token,
                    onValueChange = { token = it; error = null; message = null },
                    modifier = Modifier.weight(1f),
                    inputFocusRequester = entryFocusRequester,
                    keyboardType = KeyboardType.Password,
                    obscureText = !showToken
                )
                TvActionButton(if (showToken) "隐藏" else "显示", onClick = { showToken = !showToken })
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvActionButton(if (busy) "验证中…" else "验证并保存", primary = true, onClick = {
                    if (busy) return@TvActionButton
                    if (token.isBlank()) {
                        error = "请输入 TMDB API Read Access Token"
                    } else {
                        busy = true
                        message = null
                        error = null
                        scope.launch {
                            try {
                                repository.validateToken(token)
                                withContext(Dispatchers.IO) { settings.saveToken(token) }
                                repository.clearCache()
                                configured = true
                                token = ""
                                message = "TMDB Token 验证成功，配置已保存。"
                            } catch (failure: Exception) {
                                error = failure.message ?: "Token 验证失败，请检查网络和凭证。"
                            } finally {
                                busy = false
                            }
                        }
                    }
                })
                if (configured) TvActionButton("清除配置", destructive = true, onClick = {
                    if (busy) return@TvActionButton
                    settings.clearToken()
                    repository.clearCache()
                    configured = false
                    token = ""
                    error = null
                    message = "TMDB 配置已清除，详情页将使用资源站信息。"
                })
            }
            if (busy) {
                Spacer(Modifier.height(14.dp))
                Text("正在验证 TMDB 连接…", color = ScreenMuted, fontSize = 12.sp)
            }
            message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = AccentGold, fontSize = 12.sp)
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Color(0xFFFF9C8C), fontSize = 12.sp)
            }
            Spacer(Modifier.height(22.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .08f)))
            Spacer(Modifier.height(18.dp))
            Text("剧集信息缓存", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Text(
                "每次打开详情都会刷新访问时间；超过所选时长未访问的缓存会自动清理，下次打开时重新从 TMDB 获取。",
                color = ScreenMuted,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(13.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                listOf(1, 3, 7, 14, 30).forEach { days ->
                    TvActionButton(
                        if (days == 1) "1 天" else "$days 天",
                        selected = cacheRetentionDays == days,
                        onClick = {
                            settings.saveCacheRetentionDays(days)
                            cacheRetentionDays = days
                            scope.launch(Dispatchers.IO) { repository.pruneExpiredCache() }
                            message = "TMDB 缓存保留时长已设为 $days 天。"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuConfigurationPage(
    settings: NavigationMenuSettings,
    entryFocusRequester: FocusRequester,
    onSettingsChange: (NavigationMenuSettings) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("菜单配置", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("控制左侧主菜单的显示状态和排列顺序", color = ScreenMuted, fontSize = 13.sp)
            }
            TvActionButton("恢复默认", modifier = Modifier.focusRequester(entryFocusRequester), onClick = {
                val defaults = NavigationMenuPreferences.menuItems
                onSettingsChange(NavigationMenuSettings(defaults, defaults.toSet()))
            })
        }
        Spacer(Modifier.height(22.dp))
        Text("主菜单项目", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(settings.order, key = { it }) { label ->
                val index = settings.order.indexOf(label)
                MenuConfigurationRow(
                    label = label,
                    visible = label in settings.visibleItems,
                    fixed = label == NavigationMenuPreferences.HOME_ITEM,
                    canMoveUp = index > 0,
                    canMoveDown = index < settings.order.lastIndex,
                    onToggle = {
                        val visibleItems = if (label in settings.visibleItems) {
                            settings.visibleItems - label
                        } else {
                            settings.visibleItems + label
                        }
                        onSettingsChange(settings.copy(visibleItems = visibleItems))
                    },
                    onMove = { direction ->
                        val targetIndex = index + direction
                        if (targetIndex in settings.order.indices) {
                            val reordered = settings.order.toMutableList()
                            val movedItem = reordered.removeAt(index)
                            reordered.add(targetIndex, movedItem)
                            onSettingsChange(settings.copy(order = reordered))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MenuConfigurationRow(
    label: String,
    visible: Boolean,
    fixed: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onMove: (Int) -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0xFF151D28))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(
                if (fixed) "主页始终显示" else if (visible) "显示在左侧菜单" else "已从左侧菜单隐藏",
                color = ScreenMuted,
                fontSize = 10.sp
            )
        }
        if (fixed) {
            SettingsControlButton("固定显示", enabled = false, onClick = {})
        } else {
            SettingsControlButton(if (visible) "隐藏" else "显示", selected = !visible, onClick = onToggle)
        }
        SettingsControlButton("↑ 上移", enabled = canMoveUp, onClick = { onMove(-1) })
        SettingsControlButton("↓ 下移", enabled = canMoveDown, onClick = { onMove(1) })
    }
}

@Composable
private fun SettingsControlButton(
    label: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    focused -> Color(0xFFE7EBE7)
                    !enabled -> Color(0xFF1C242E)
                    selected -> Color(0xFF594A30)
                    else -> Color(0xFF27313D)
                }
            )
            .border(
                if (focused) 2.dp else if (enabled) 1.dp else 0.dp,
                if (focused) Color.White else Color.White.copy(alpha = .10f),
                shape
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick).focusable() else Modifier)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = when {
                focused -> Color(0xFF172026)
                !enabled -> ScreenMuted.copy(alpha = .55f)
                else -> Color.White
            },
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun SettingsMenuItem(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    focused -> Color(0xFFE7EBE7)
                    selected -> Color(0xFF26313D)
                    else -> Color.Transparent
                }
            )
            .border(
                if (focused) 2.dp else if (selected) 1.dp else 0.dp,
                if (focused) Color.White else Color.White.copy(alpha = .12f),
                shape
            )
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (focused) Color(0xFF172026) else if (selected) AccentGold else Color.White,
            fontSize = 14.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun SourceRow(
    source: VideoSource,
    testing: Boolean,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDefault: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF151D28)).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(source.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    if (source.isDefault) Text("默认", color = AccentGold, fontSize = 11.sp)
                    if (!source.enabled) Text("已停用", color = ScreenMuted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(5.dp))
                Text(source.api, color = ScreenMuted, fontSize = 11.sp, maxLines = 1)
            }
            TvActionButton(if (source.enabled) "已启用" else "已停用", selected = source.enabled) { onToggle(!source.enabled) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvActionButton(if (source.isDefault) "默认站点" else "设为默认", selected = source.isDefault, onClick = onDefault)
            TvActionButton(if (testing) "测试中…" else "测试连通", onClick = onTest)
            TvActionButton("编辑", onClick = onEdit)
            TvActionButton("删除", destructive = true, onClick = onDelete)
            if (source.lastLatencyMs != null) Text("上次 ${source.lastLatencyMs} ms", Modifier.align(Alignment.CenterVertically).padding(start = 6.dp), color = ScreenMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SourceEditorDialog(initial: VideoSource?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var api by remember(initial?.id) { mutableStateOf(initial?.api.orEmpty()) }
    var detail by remember(initial?.id) { mutableStateOf(initial?.detailUrl.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(720.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF18212D)).padding(28.dp)) {
            Text(if (initial == null) "添加视频源" else "编辑视频源", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("支持 MacCMS / 苹果 CMS 兼容的 JSON API 地址", color = ScreenMuted, fontSize = 12.sp)
            Spacer(Modifier.height(18.dp))
            InputBox("站点名称", name, { name = it })
            Spacer(Modifier.height(12.dp))
            InputBox("API 地址", api, { api = it }, keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(12.dp))
            InputBox("站点主页（可选）", detail, { detail = it }, keyboardType = KeyboardType.Uri)
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error.orEmpty(), color = Color(0xFFFF9C8C), fontSize = 12.sp)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TvActionButton("取消", onClick = onDismiss)
                Spacer(Modifier.width(10.dp))
                TvActionButton("保存", primary = true) {
                    val uri = runCatching { android.net.Uri.parse(api.trim()) }.getOrNull()
                    if (name.isBlank()) error = "请输入站点名称"
                    else if (uri?.host.isNullOrBlank() || (uri?.scheme?.lowercase() !in listOf("http", "https"))) error = "请输入有效的 HTTP(S) API 地址"
                    else onSave(name, api, detail)
                }
            }
        }
    }
}

@Composable
private fun ImportSourcesDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var raw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(780.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF18212D)).padding(28.dp)) {
            Text("导入 MacCMS 配置", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("粘贴包含 api_site 的 JSON。站点会保存在本机，不会自动访问，测试或搜索时才会请求。", color = ScreenMuted, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            InputBox("JSON 配置", raw, { raw = it }, singleLine = false, minLines = 8, keyboardType = KeyboardType.Text)
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error.orEmpty(), color = Color(0xFFFF9C8C), fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TvActionButton("取消", onClick = onDismiss)
                Spacer(Modifier.width(10.dp))
                TvActionButton("导入配置", primary = true) {
                    if (raw.isBlank()) error = "请粘贴 JSON 配置" else onImport(raw)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteVideoDetailScreen(
    repository: MacCmsRepository,
    tmdbSettings: TmdbSettingsStore,
    tmdbRepository: TmdbMetadataRepository,
    entryFocusRequester: FocusRequester,
    initial: RemoteVideo,
    onPlay: (RemoteVideo, PlaybackLine, PlaybackEpisode) -> Unit
) {
    var detail by remember(initial.id) { mutableStateOf<RemoteVideoDetail?>(null) }
    var loading by remember(initial.id) { mutableStateOf(true) }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    var tmdbDetails by remember(initial.id) { mutableStateOf<TmdbTvMetadata?>(null) }
    var tmdbLoading by remember(initial.id) { mutableStateOf(false) }
    var tmdbError by remember(initial.id) { mutableStateOf<String?>(null) }
    var lineIndex by remember(initial.id) { mutableIntStateOf(0) }
    var favorite by remember(initial.id) { mutableStateOf(repository.isFavorite(initial.id)) }
    var showAllEpisodes by remember(initial.id) { mutableStateOf(false) }
    var episodesDescending by remember(initial.id) { mutableStateOf(false) }
    val firstAllEpisodeFocusRequester = remember(initial.id) { FocusRequester() }
    val selectedLineFocusRequester = remember(initial.id) { FocusRequester() }
    val viewAllEpisodesFocusRequester = remember(initial.id) { FocusRequester() }
    val firstPreviewEpisodeFocusRequester = remember(initial.id) { FocusRequester() }
    val detailListState = rememberLazyListState()
    val preserveDetailTopOnEntry = remember { mutableStateOf(true) }
    val platformBringIntoViewSpec = LocalBringIntoViewSpec.current
    val detailBringIntoViewSpec = remember(platformBringIntoViewSpec, preserveDetailTopOnEntry) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                if (preserveDetailTopOnEntry.value) {
                    MinimalFocusBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
                } else {
                    platformBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
                }
        }
    }
    var showSources by remember(initial.id) { mutableStateOf(false) }
    val detailScope = rememberCoroutineScope()

    fun returnToDetailTop() {
        preserveDetailTopOnEntry.value = true
        detailScope.launch {
            if (preserveDetailTopOnEntry.value) detailListState.scrollToItem(0)
        }
    }

    fun heroActionFocusModifier(): Modifier = Modifier
        .onFocusChanged { if (it.isFocused) returnToDetailTop() }
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown &&
                detail?.lines?.firstOrNull() != null
            ) {
                preserveDetailTopOnEntry.value = false
                detailScope.launch {
                    detailListState.scrollToItem(1)
                    withFrameNanos { }
                    runCatching { selectedLineFocusRequester.requestFocus() }
                }
                true
            } else false
        }
    LaunchedEffect(initial.id) {
        preserveDetailTopOnEntry.value = true
        detailListState.scrollToItem(0)
        runCatching { entryFocusRequester.requestFocus() }
        val earlyTmdbRequest = if (tmdbSettings.isConfigured() && isLikelySeries(initial, null)) {
            tmdbLoading = true
            async { tmdbRepository.loadDetails(initial) }
        } else null
        var resolved: RemoteVideoDetail? = null
        try {
            resolved = repository.detail(initial)
            detail = resolved
        } catch (failure: Exception) {
            error = failure.message ?: "影片详情加载失败"
        } finally {
            loading = false
        }
        val video = resolved?.video ?: initial
        if (earlyTmdbRequest != null) {
            try {
                tmdbDetails = earlyTmdbRequest.await()
                if (tmdbDetails == null) tmdbError = "TMDB 暂无匹配剧集，继续显示资源站信息。"
            } catch (failure: Exception) {
                tmdbError = "TMDB 信息加载失败，继续显示资源站信息。"
            } finally {
                tmdbLoading = false
            }
        } else if (tmdbSettings.isConfigured() && isLikelySeries(video, resolved)) {
            tmdbLoading = true
            try {
                tmdbDetails = tmdbRepository.loadDetails(video)
                if (tmdbDetails == null) tmdbError = "TMDB 暂无匹配剧集，继续显示资源站信息。"
            } catch (failure: Exception) {
                tmdbError = "TMDB 信息加载失败，继续显示资源站信息。"
            } finally {
                tmdbLoading = false
            }
        }
    }
    val video = detail?.video ?: initial
    val lines = detail?.lines.orEmpty()
    val selectedLine = lines.getOrNull(lineIndex.coerceIn(0, (lines.size - 1).coerceAtLeast(0)))
    val displayTitle = tmdbDetails?.title?.takeIf(String::isNotBlank) ?: video.title
    val requestedSeason = Regex("(?i)(?:第\\s*(\\d+)\\s*季|S(\\d{1,2})(?:\\b|$))")
        .find(video.title)?.groupValues?.drop(1)?.firstNotNullOfOrNull { it.toIntOrNull() }
    val tmdbSeason = tmdbDetails?.seasons?.firstOrNull { it.number == requestedSeason }
        ?: tmdbDetails?.seasons?.firstOrNull()
    val description = tmdbSeason?.let { it.overview }?.takeIf(String::isNotBlank)
        ?: tmdbDetails?.overview?.takeIf(String::isNotBlank)
        ?: video.description
    val displayYear = tmdbDetails?.year?.takeIf(String::isNotBlank) ?: video.year
    val displayRating = tmdbDetails?.rating?.takeIf { it > 0.0 }?.let { String.format("%.1f", it) }
        ?: video.rating
    val watchRecord = repository.watchRecord(video)
    val resumeTarget = watchRecord?.let { record ->
        lines.firstNotNullOfOrNull { line ->
            matchingHistoryEpisode(record, line.episodes)?.let { episode -> line to episode }
        }
    }
    val episodePoster = tmdbDetails?.posterUrl?.takeIf(String::isNotBlank) ?: video.posterUrl
    val heroArtworkUrl = tmdbDetails?.backdropUrl?.takeIf(String::isNotBlank) ?: episodePoster
    var heroArtwork by remember(heroArtworkUrl) { mutableStateOf<LoadedArtwork?>(null) }
    LaunchedEffect(heroArtworkUrl) { heroArtwork = loadArtwork(heroArtworkUrl) }

    if (showSources) {
        PlaybackSourcesDialog(repository, initial, video, onDismiss = { showSources = false }) { resolved ->
            detail = resolved
            lineIndex = 0
            error = null
            showSources = false
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides detailBringIntoViewSpec) {
        LazyColumn(
            state = detailListState,
            modifier = Modifier.fillMaxSize().background(ScreenInk).onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionUp || event.key == Key.DirectionDown)
                ) {
                    preserveDetailTopOnEntry.value = false
                }
                false
            },
            contentPadding = PaddingValues(bottom = 34.dp)
        ) {
        item {
            Box(Modifier.fillMaxWidth().height(420.dp).clipToBounds()) {
                val artwork = heroArtwork
                if (artwork != null) {
                    Image(
                        artwork.image,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.16f; scaleY = 1.22f }.blur(42.dp),
                        contentScale = ContentScale.Crop
                    )
                    Box(Modifier.fillMaxSize().background(artwork.dominantColor.copy(alpha = .20f)))
                    val portraitArtwork = artwork.image.height > artwork.image.width
                    Image(
                        artwork.image,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (portraitArtwork) .72f else .58f },
                        alignment = if (portraitArtwork) androidx.compose.ui.BiasAlignment(0f, -.5f) else Alignment.Center,
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF334354), ScreenInk))))
                }
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(colorStops = arrayOf(
                    0f to ScreenInk.copy(alpha = .88f),
                    .34f to ScreenInk.copy(alpha = .82f),
                    .56f to ScreenInk.copy(alpha = .44f),
                    .76f to Color.Transparent,
                    1f to Color.Transparent
                ))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(colorStops = arrayOf(
                    0f to ScreenInk.copy(alpha = .22f),
                    .52f to Color.Transparent,
                    .73f to ScreenInk.copy(alpha = .30f),
                    .90f to ScreenInk.copy(alpha = .82f),
                    1f to ScreenInk
                ))))
                Column(
                    Modifier.align(Alignment.CenterStart)
                        .padding(start = 140.dp, end = 40.dp)
                        .widthIn(max = 780.dp)
                ) {
                    Text(displayTitle, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    if (tmdbDetails?.originalTitle?.isNotBlank() == true && tmdbDetails?.originalTitle != displayTitle) {
                        Spacer(Modifier.height(5.dp))
                        Text(tmdbDetails!!.originalTitle, color = ScreenMuted, fontSize = 13.sp, maxLines = 1)
                    }
                    Spacer(Modifier.height(10.dp))
                    val metadata = buildList {
                        if (displayYear.isNotBlank()) add(displayYear)
                        add(video.category.ifBlank { "剧集" })
                        if (displayRating.isNotBlank()) add("★ $displayRating")
                        tmdbDetails?.runtimeMinutes?.takeIf { it > 0 }?.let { add("$it 分钟/集") }
                        if (tmdbDetails?.genres?.isNotEmpty() == true) add(tmdbDetails!!.genres.joinToString(" / "))
                    }.joinToString("　·　")
                    Text(metadata, color = AccentGold, fontSize = 13.sp, maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                    Text("播放来源：${video.sourceName}", color = ScreenMuted, fontSize = 12.sp, maxLines = 1)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val firstEpisode = selectedLine?.episodes?.firstOrNull()
                        val continueTarget = resumeTarget ?: watchRecord?.let { record ->
                            PlaybackLine(record.lineName, listOf(record.episode)) to record.episode
                        }
                        TvActionButton(
                            if (watchRecord != null) "▶  继续播放" else "▶  播放",
                            primary = true,
                            modifier = heroActionFocusModifier().focusRequester(entryFocusRequester)
                        ) {
                            val target = continueTarget
                            if (watchRecord != null && target != null) onPlay(video, target.first, target.second)
                            else if (firstEpisode != null && selectedLine != null) onPlay(video, selectedLine, firstEpisode)
                        }
                        TvActionButton(
                            if (favorite) "✓ 已收藏" else "＋ 加入收藏",
                            selected = favorite,
                            modifier = heroActionFocusModifier()
                        ) {
                            favorite = !favorite
                            repository.setFavorite(video, favorite)
                        }
                    }
                    if (tmdbLoading) {
                        Spacer(Modifier.height(8.dp))
                        Text("正在补充 TMDB 信息…", color = ScreenMuted, fontSize = 11.sp)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 108.dp, end = 40.dp)) {
                Spacer(Modifier.height(26.dp))
                Text("简介", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(description.ifBlank { "该站点没有提供影片简介。" }, color = ScreenMuted, fontSize = 14.sp, lineHeight = 22.sp, maxLines = 5)
                Spacer(Modifier.height(18.dp))
                if (loading) Text("正在加载详情和播放线路…", color = ScreenMuted, fontSize = 13.sp)
                if (error != null) Text("详情加载失败：${error.orEmpty()}", color = Color(0xFFFF9C8C), fontSize = 12.sp)
                if (lines.isEmpty() && !loading && error == null) Text("站点未提供可播放的选集", color = ScreenMuted, fontSize = 13.sp)
            }
        }
        if (lines.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(start = 108.dp, end = 40.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("播放来源", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(16.dp))
                        TvActionButton("${video.sourceName}  ⇄  切换资源站") { showSources = true }
                    }
                    Spacer(Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        itemsIndexed(lines.take(1)) { index, line ->
                            TvActionButton(
                                line.name,
                                selected = lineIndex == index,
                                modifier = Modifier
                                    .then(if (lineIndex == index) Modifier.focusRequester(selectedLineFocusRequester) else Modifier)
                                    .focusProperties {
                                        if (selectedLine?.episodes?.isNotEmpty() == true) {
                                            down = viewAllEpisodesFocusRequester
                                        }
                                    }
                            ) { lineIndex = index }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("剧集", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(14.dp))
                        Text("${selectedLine?.episodes?.size ?: 0} 集", color = ScreenMuted, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        if ((selectedLine?.episodes?.size ?: 0) > 0) {
                            TvActionButton(
                                "查看全部  ›",
                                modifier = Modifier
                                    .focusRequester(viewAllEpisodesFocusRequester)
                                    .focusProperties {
                                        up = selectedLineFocusRequester
                                        down = firstPreviewEpisodeFocusRequester
                                    }
                            ) { showAllEpisodes = true }
                        }
                        if (tmdbLoading) {
                            Spacer(Modifier.width(12.dp))
                            Text("正在加载分集图片…", color = ScreenMuted, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            selectedLine?.let { line ->
                item {
                    Box(Modifier.fillMaxWidth().padding(start = 108.dp, end = 40.dp)) {
                        val previewEpisodes = line.episodes.take(6)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 4.dp)) {
                            itemsIndexed(previewEpisodes, key = { _, episode -> episode.url }) { index, episode ->
                                val sourceEpisodeNumber = episodeNumber(episode.title) ?: index + 1
                                val tmdbEpisode = tmdbSeason?.episodes?.firstOrNull { it.number == sourceEpisodeNumber }
                                EpisodeArtworkCard(
                                    episode = episode,
                                    number = sourceEpisodeNumber,
                                    title = tmdbEpisode?.name?.takeIf(String::isNotBlank) ?: episode.title,
                                    imageUrl = tmdbEpisode?.stillUrl?.takeIf(String::isNotBlank)
                                        ?: tmdbSeason?.posterUrl?.takeIf(String::isNotBlank)
                                        ?: tmdbDetails?.posterUrl?.takeIf(String::isNotBlank)
                                        ?: video.posterUrl,
                                    modifier = Modifier.width(224.dp).then(
                                        if (index == 0) {
                                            Modifier.focusRequester(firstPreviewEpisodeFocusRequester)
                                                .focusProperties { up = viewAllEpisodesFocusRequester }
                                        } else Modifier
                                    ),
                                    onClick = { onPlay(video, line, episode) }
                                )
                            }
                        }
                    }
                }
            }
        }
        tmdbDetails?.let { metadata ->
            if (metadata.directors.isNotEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(start = 108.dp, end = 40.dp)) {
                        Spacer(Modifier.height(28.dp)); TmdbPeopleSection("导演", metadata.directors)
                    }
                }
            }
            if (metadata.cast.isNotEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(start = 108.dp, end = 40.dp)) {
                        Spacer(Modifier.height(28.dp)); TmdbPeopleSection("演员", metadata.cast)
                    }
                }
            }
        }
    }
    }

    if (showAllEpisodes) {
        val line = selectedLine
        if (line != null) {
            val orderedEpisodes = if (episodesDescending) line.episodes.asReversed() else line.episodes
            LaunchedEffect(showAllEpisodes, orderedEpisodes.size) {
                if (showAllEpisodes && orderedEpisodes.isNotEmpty()) {
                    runCatching { firstAllEpisodeFocusRequester.requestFocus() }
                }
            }
            Dialog(
                onDismissRequest = { showAllEpisodes = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Column(Modifier.fillMaxSize().background(ScreenInk).padding(horizontal = 34.dp, vertical = 24.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TvActionButton("‹ 返回") { showAllEpisodes = false }
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${tmdbSeason?.name?.takeIf(String::isNotBlank) ?: "第 ${tmdbSeason?.number ?: requestedSeason ?: 1} 季"} · 全部剧集", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(3.dp))
                            Text("${line.episodes.size} 集　·　${line.name}", color = ScreenMuted, fontSize = 12.sp)
                        }
                        TvActionButton(if (episodesDescending) "↑ 倒序" else "↓ 正序", selected = episodesDescending) {
                            episodesDescending = !episodesDescending
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(224.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(bottom = 26.dp)
                    ) {
                        itemsIndexed(orderedEpisodes, key = { _, episode -> episode.url }) { index, episode ->
                            val sourceEpisodeNumber = episodeNumber(episode.title) ?: (if (episodesDescending) line.episodes.size - index else index + 1)
                            val tmdbEpisode = tmdbSeason?.episodes?.firstOrNull { it.number == sourceEpisodeNumber }
                            EpisodeArtworkCard(
                                episode = episode,
                                number = sourceEpisodeNumber,
                                title = tmdbEpisode?.name?.takeIf(String::isNotBlank) ?: episode.title,
                                imageUrl = tmdbEpisode?.stillUrl?.takeIf(String::isNotBlank)
                                    ?: tmdbSeason?.posterUrl?.takeIf(String::isNotBlank)
                                    ?: tmdbDetails?.posterUrl?.takeIf(String::isNotBlank)
                                    ?: video.posterUrl,
                                modifier = Modifier.fillMaxWidth().then(
                                    if (index == 0) Modifier.focusRequester(firstAllEpisodeFocusRequester) else Modifier
                                ),
                                onClick = { showAllEpisodes = false; onPlay(video, line, episode) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun isLikelySeries(video: RemoteVideo, detail: RemoteVideoDetail?): Boolean {
    val category = video.category.lowercase()
    if (Regex("电视剧|剧集|连续剧|番剧|动漫|动画|短剧|韩剧|日剧|美剧|泰剧|网剧|series|season|tv").containsMatchIn(category)) return true
    if (Regex("(?i)(?:第\\s*\\d+\\s*季|S\\d{1,2}E?\\d*)").containsMatchIn(video.title)) return true
    return detail?.lines?.any { it.episodes.size > 1 } == true
}

private fun episodeNumber(title: String): Int? {
    val match = Regex("(?i)(?:第\\s*0*(\\d+)\\s*集|(?:EP?|E)\\s*0*(\\d+)|S\\d+E\\s*0*(\\d+))").find(title)
    return match?.groupValues?.drop(1)?.firstNotNullOfOrNull { it.toIntOrNull() }
}

@Composable
private fun EpisodeArtworkCard(
    episode: PlaybackEpisode,
    number: Int,
    title: String,
    imageUrl: String,
    modifier: Modifier = Modifier.width(224.dp),
    onClick: () -> Unit
) {
    var focused by remember(episode.url) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) Color(0xFF263547) else Color(0xFF151D28))
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) AccentGold else Color.White.copy(alpha = .08f),
                shape = shape
            )
            .clickable(onClick = onClick).focusable()
            .padding(4.dp)
    ) {
        Box(Modifier.fillMaxWidth().height(126.dp).clip(RoundedCornerShape(6.dp))) {
            RemoteArtwork(imageUrl, title.ifBlank { episode.title }, Modifier.fillMaxSize())
            Text(
                "第 $number 集",
                Modifier.align(Alignment.BottomStart).padding(7.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color(0xCC090D13))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
                color = Color.White,
                fontSize = 10.sp
            )
            if (focused) {
                Text(
                    "正在选择",
                    Modifier.align(Alignment.TopEnd).padding(7.dp)
                        .clip(RoundedCornerShape(4.dp)).background(AccentGold)
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    color = Color(0xFF172026),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            title.ifBlank { episode.title },
            Modifier.padding(horizontal = 5.dp),
            color = if (focused) AccentGold else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun TmdbPeopleSection(title: String, people: List<TmdbPerson>) {
    Column {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(people) { _, person ->
                Column(Modifier.width(116.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    RemoteArtwork(
                        person.profileUrl,
                        person.name,
                        Modifier.size(84.dp).clip(RoundedCornerShape(42.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(person.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (person.role.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(person.role, color = ScreenMuted, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun TvVideoPlayerScreen(
    repository: MacCmsRepository,
    video: RemoteVideo,
    episode: PlaybackEpisode,
    videoTitle: String,
    lineName: String,
    episodes: List<PlaybackEpisode>,
    startPositionMs: Long = 0L,
    onSelectEpisode: (PlaybackEpisode) -> Unit,
    onExit: () -> Unit,
    onSwitchSource: (RemoteVideo, PlaybackLine, PlaybackEpisode, Long) -> Unit,
    onProgress: (Long, Long) -> Unit
) {
    var playbackError by remember(episode.url) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val activity = context as? Activity
    DisposableEffect(episode.url) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(video.id, episode.url, startPositionMs) {
        withContext(Dispatchers.IO) {
            repository.saveWatchProgress(video, lineName, episode, startPositionMs, 0L)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PlayerSurface(
            repository = repository,
            video = video,
            onSwitchSource = onSwitchSource,
            url = episode.url,
            episode = episode,
            episodes = episodes,
            startPositionMs = startPositionMs,
            onSelectEpisode = onSelectEpisode,
            onExit = onExit,
            onError = { playbackError = it },
            onProgress = onProgress
        )
        Column(Modifier.align(Alignment.TopStart).padding(28.dp)) {
            Text(videoTitle, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("$lineName　·　${episode.title}", color = ScreenMuted, fontSize = 12.sp)
        }
        if (playbackError != null) {
            Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Color(0xE0202935)).padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("无法播放此视频", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(playbackError.orEmpty(), color = ScreenMuted, fontSize = 12.sp, maxLines = 3)
                Spacer(Modifier.height(14.dp))
                Text("按返回键选择其他线路或视频", color = AccentGold, fontSize = 12.sp)
            }
        }
    }
}

@UnstableApi
@Composable
private fun PlayerSurface(
    repository: MacCmsRepository,
    video: RemoteVideo,
    onSwitchSource: (RemoteVideo, PlaybackLine, PlaybackEpisode, Long) -> Unit,
    url: String,
    episode: PlaybackEpisode,
    episodes: List<PlaybackEpisode>,
    startPositionMs: Long,
    onSelectEpisode: (PlaybackEpisode) -> Unit,
    onExit: () -> Unit,
    onError: (String) -> Unit,
    onProgress: (Long, Long) -> Unit
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    val errorCallback by rememberUpdatedState(onError)
    val progressCallback by rememberUpdatedState(onProgress)
    val selectEpisodeCallback by rememberUpdatedState(onSelectEpisode)
    var showEpisodePanel by remember { mutableStateOf(false) }
    var showSourceSearch by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var playbackSpeed by remember(player) { mutableFloatStateOf(1f) }
    val availableEpisodes = episodes.ifEmpty { listOf(episode) }
    val currentEpisodeIndex = availableEpisodes.indexOfFirst { it.url == episode.url }.coerceAtLeast(0)
    val episodeListState = rememberLazyListState((currentEpisodeIndex - 3).coerceAtLeast(0))
    val currentEpisodeFocusRequester = remember { FocusRequester() }
    val selectedSpeedFocusRequester = remember { FocusRequester() }
    val closePanelFocusRequester = remember { FocusRequester() }
    val showPanelState = rememberUpdatedState(showEpisodePanel)
    val openPanelCallback = rememberUpdatedState<() -> Unit>({ showEpisodePanel = true })

    var controllerVisible by remember { mutableStateOf(false) }
    BackHandler(enabled = showEpisodePanel || controllerVisible) {
        showEpisodePanel = false
        playerView?.hideController()
    }

    LaunchedEffect(showEpisodePanel, currentEpisodeIndex) {
        if (showEpisodePanel) {
            episodeListState.scrollToItem((currentEpisodeIndex - 3).coerceAtLeast(0))
        }
    }
    LaunchedEffect(showEpisodePanel, currentEpisodeIndex, playerView) {
        if (showEpisodePanel) {
            playerView?.hideController()
            runCatching { currentEpisodeFocusRequester.requestFocus() }
        } else {
            playerView?.requestFocus()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorCallback(error.localizedMessage ?: "播放器无法读取此地址")
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, url, startPositionMs) {
        val mediaBuilder = PlayerMediaItem.Builder().setUri(url)
        if (url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) mediaBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        player.setMediaItem(mediaBuilder.build(), startPositionMs.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(player, url) {
        while (true) {
            delay(5_000)
            val duration = player.duration
            if (duration > 0) {
                val position = player.currentPosition.coerceAtLeast(0L)
                withContext(Dispatchers.IO) { progressCallback(position, duration) }
            }
        }
    }
    if (showSourceSearch) {
        PlaybackSourcesDialog(
            repository = repository,
            original = video,
            current = video,
            requiredEpisodeNumber = episodeNumber(episode.title) ?: currentEpisodeIndex + 1,
            onDismiss = { showSourceSearch = false }
        ) { resolved ->
            val line = resolved.lines.first()
            val number = episodeNumber(episode.title) ?: currentEpisodeIndex + 1
            val target = line.episodes.firstOrNull { episodeNumber(it.title) == number }
                ?: line.episodes[number - 1]
            val position = player.currentPosition.coerceAtLeast(0L)
            showSourceSearch = false
            showEpisodePanel = false
            onSwitchSource(resolved.video, line, target, position)
        }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                EpisodeAwarePlayerView(viewContext).apply {
                    useController = true
                    controllerAutoShow = true
                    controllerHideOnTouch = false
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        controllerVisible = visibility == android.view.View.VISIBLE
                    })
                    this.player = player
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    onDownPressed = {
                        if (!showPanelState.value) {
                            openPanelCallback.value()
                            true
                        } else false
                    }
                    playerView = this
                }
            },
            update = { view ->
                view.player = player
                (view as? EpisodeAwarePlayerView)?.onDownPressed = {
                    if (!showPanelState.value) {
                        openPanelCallback.value()
                        true
                    } else false
                }
                playerView = view
            },
            onRelease = { view ->
                view.player = null
                if (playerView === view) playerView = null
            }
        )
        if (showEpisodePanel) {
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 44.dp, vertical = 28.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xF20B1119))
                    .border(1.dp, Color.White.copy(alpha = .16f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 18.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("选集", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(12.dp))
                    val currentNumber = episodeNumber(episode.title) ?: currentEpisodeIndex + 1
                    Text("第 $currentNumber 集  /  共 ${availableEpisodes.size} 集", color = ScreenMuted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(
                    state = episodeListState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    itemsIndexed(availableEpisodes, key = { _, item -> item.url }) { index, item ->
                        val number = episodeNumber(item.title) ?: index + 1
                        PlayerPanelButton(
                            label = "$number",
                            selected = index == currentEpisodeIndex,
                            modifier = Modifier.width(64.dp)
                                .then(if (index == currentEpisodeIndex) Modifier.focusRequester(currentEpisodeFocusRequester) else Modifier)
                                .focusProperties { down = selectedSpeedFocusRequester },
                            onClick = { selectEpisodeCallback(item) }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("播放倍数", color = ScreenMuted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEachIndexed { index, speed ->
                        if (index > 0) Spacer(Modifier.width(7.dp))
                        val speedLabel = if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed}×"
                        PlayerPanelButton(
                            label = speedLabel,
                            selected = playbackSpeed == speed,
                            modifier = Modifier
                                .then(if (playbackSpeed == speed) Modifier.focusRequester(selectedSpeedFocusRequester) else Modifier)
                                .focusProperties {
                                    up = currentEpisodeFocusRequester
                                    down = closePanelFocusRequester
                                },
                            onClick = {
                                playbackSpeed = speed
                                player.setPlaybackSpeed(speed)
                            }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PlayerPanelButton(
                        label = "切换视频来源",
                        modifier = Modifier.focusRequester(closePanelFocusRequester)
                            .focusProperties { up = selectedSpeedFocusRequester },
                        onClick = { showSourceSearch = true }
                    )
                    Spacer(Modifier.width(10.dp))
                    PlayerPanelButton(
                        label = "回到详情页",
                        modifier = Modifier
                            .focusProperties { up = selectedSpeedFocusRequester },
                        onClick = onExit
                    )
                    Spacer(Modifier.weight(1f))
                    PlayerPanelButton(
                        label = "收起",
                        modifier = Modifier
                            .focusProperties { up = selectedSpeedFocusRequester },
                        onClick = { showEpisodePanel = false }
                    )
                }
            }
        }
    }
}

@UnstableApi
private class EpisodeAwarePlayerView(context: android.content.Context) : PlayerView(context) {
    var onDownPressed: (() -> Boolean)? = null
    private var touchStartY = Float.NaN

    override fun onInterceptTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> touchStartY = event.y
            android.view.MotionEvent.ACTION_MOVE -> {
                val swipeDistance = event.y - touchStartY
                if (!touchStartY.isNaN() && swipeDistance > 64f * resources.displayMetrics.density &&
                    onDownPressed?.invoke() == true
                ) return true
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> touchStartY = Float.NaN
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN &&
            onDownPressed?.invoke() == true
        ) return true
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun PlayerPanelButton(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    focused -> Color(0xFFE7EBE7)
                    selected -> Color(0xFF594A30)
                    else -> Color(0xFF27313D)
                }
            )
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) Color.White else if (selected) AccentGold else Color.White.copy(alpha = .12f),
                shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .focusable(enabled = enabled)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = when {
                focused -> Color(0xFF172026)
                !enabled -> ScreenMuted.copy(alpha = .5f)
                selected -> AccentGold
                else -> Color.White
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun VideoResultCard(
    video: RemoteVideo,
    onClick: () -> Unit,
    progress: Float? = null,
    modifier: Modifier = Modifier,
    posterAspectRatio: Float = 0.85f,
    onFocused: () -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.onFocusChanged {
        focused = it.isFocused
        if (it.isFocused) onFocused()
    }
        .clip(shape)
        .background(if (focused) Color(0xFF263547) else Color(0xFF151D28))
        .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = .08f), shape)
        .clickable(onClick = onClick)
        .focusable()
        .padding(4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(posterAspectRatio).clip(RoundedCornerShape(7.dp))) {
            RemoteArtwork(video.posterUrl, video.title, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD090D13)))))
            if (video.rating.isNotBlank()) Text("★ ${video.rating}", Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xB9090D13)).padding(horizontal = 7.dp, vertical = 4.dp), color = AccentGold, fontSize = 10.sp)
            Text(video.sourceName, Modifier.align(Alignment.BottomStart).padding(9.dp), color = Color.White.copy(alpha = .85f), fontSize = 9.sp, maxLines = 1)
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(AccentGold))
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(video.title, Modifier.padding(horizontal = 5.dp), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(listOf(video.year, video.category).filter(String::isNotBlank).joinToString(" · "), Modifier.padding(horizontal = 5.dp, vertical = 2.dp), color = ScreenMuted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun RemoteArtwork(url: String, title: String, modifier: Modifier = Modifier) {
    var artwork by remember(url) { mutableStateOf<LoadedArtwork?>(null) }
    LaunchedEffect(url) { artwork = loadArtwork(url) }
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF334354), Color(0xFF151B27)))), contentAlignment = Alignment.Center) {
        if (artwork != null) Image(artwork!!.image, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(title.take(1), color = Color.White.copy(alpha = .72f), fontSize = 44.sp, fontWeight = FontWeight.Bold)
    }
}

private data class LoadedArtwork(val image: ImageBitmap, val dominantColor: Color)

private suspend fun loadArtwork(url: String): LoadedArtwork? {
    if (url.isBlank()) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(url).openConnection().apply { connectTimeout = 7000; readTimeout = 9000 }
            val bitmap = connection.getInputStream().use { BitmapFactory.decodeStream(it) } ?: return@runCatching null
            val stepX = (bitmap.width / 24).coerceAtLeast(1)
            val stepY = (bitmap.height / 24).coerceAtLeast(1)
            var red = 0L
            var green = 0L
            var blue = 0L
            var count = 0L
            var y = 0
            while (y < bitmap.height) {
                var x = 0
                while (x < bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    if (android.graphics.Color.alpha(pixel) > 127) {
                        red += android.graphics.Color.red(pixel)
                        green += android.graphics.Color.green(pixel)
                        blue += android.graphics.Color.blue(pixel)
                        count++
                    }
                    x += stepX
                }
                y += stepY
            }
            val denominator = count.coerceAtLeast(1L).toFloat() * 255f
            LoadedArtwork(
                image = bitmap.asImageBitmap(),
                dominantColor = Color(red / denominator, green / denominator, blue / denominator)
            )
        }.getOrNull()
    }
}

@Composable
private fun InputBox(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    inputFocusRequester: FocusRequester? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    obscureText: Boolean = false,
    onSubmit: (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF202B38))
        .border(if (focused) 2.dp else 1.dp, if (focused) AccentGold else Color.White.copy(alpha = .12f), RoundedCornerShape(8.dp))
        .padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(label, color = if (focused) AccentGold else ScreenMuted, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth()
                .then(inputFocusRequester?.let(Modifier::focusRequester) ?: Modifier)
                .onFocusChanged { focused = it.isFocused }
                .then(if (onSubmit != null) Modifier.onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.NumPadEnter)) { onSubmit(); true } else false
                } else Modifier),
            singleLine = singleLine,
            minLines = minLines,
            visualTransformation = if (obscureText) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = if (onSubmit != null) ImeAction.Search else ImeAction.Next),
            keyboardActions = KeyboardActions(onSearch = { onSubmit?.invoke() }, onDone = { onSubmit?.invoke() }),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("点击输入", color = Color.White.copy(alpha = .38f), fontSize = 14.sp)
                    inner()
                }
            }
        )
    }
}

@Composable
private fun TvActionButton(
    label: String,
    primary: Boolean = false,
    selected: Boolean = false,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(9.dp)
    Box(modifier.onFocusChanged { focused = it.isFocused }
        .clip(shape)
        .background(when { focused -> Color(0xFFE7EBE7); primary -> Color(0xFF435362); selected -> Color(0xFF594A30); destructive -> Color(0xFF583733); else -> Color(0xFF27313D) })
        .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = .10f), shape)
        .clickable(onClick = onClick).focusable().padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (focused) Color(0xFF172026) else if (destructive) Color(0xFFFFC5B9) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun PlaybackSourcesDialog(
    repository: MacCmsRepository,
    original: RemoteVideo,
    current: RemoteVideo,
    requiredEpisodeNumber: Int? = null,
    onDismiss: () -> Unit,
    onSelect: (RemoteVideoDetail) -> Unit
) {
    var matches by remember { mutableStateOf(listOf(current)) }
    var completed by remember { mutableIntStateOf(0) }
    val sources = remember { repository.sources().filter { it.enabled && !it.isAdult } }
    var selecting by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var failedSources by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        firstFocus.requestFocus()
        coroutineScope {
            sources.map { source -> launch {
                try {
                    val found = repository.searchSource(source, original.title)
                    val normalize: (String) -> String = { it.filter { c -> c.isLetterOrDigit() }.lowercase() }
                    matches = (matches + found.filter { normalize(it.title) == normalize(original.title) }).distinctBy { it.id }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failedSources++
                } finally {
                    completed++
                }
            } }.forEach { it.join() }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(760.dp).heightIn(max = 560.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF18212D)).padding(28.dp)) {
            Text("选择资源站", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("正在搜索同名剧集，选择后使用该站点的第一条线路。", color = ScreenMuted, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Text("$completed / ${sources.size}  ·  ${if (completed < sources.size) "正在查询普通源" else "查询完成"}${if (failedSources > 0) " · $failedSources 个站点请求失败" else ""}", color = AccentGold, fontSize = 14.sp)
            if (failure != null) Text(failure.orEmpty(), color = Color(0xFFFF9C8C), fontSize = 14.sp)
            if (selecting) Text("正在加载播放线路…", color = ScreenMuted, fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(matches, key = { _, item -> item.id }) { index, item ->
                    PlaybackSourceCard(
                        video = item,
                        selected = item.id == current.id,
                        modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                    ) {
                        if (!selecting) scope.launch {
                            selecting = true
                            failure = null
                            try {
                                val resolved = repository.detail(item)
                                if (resolved.lines.firstOrNull()?.episodes.isNullOrEmpty()) failure = "该站点暂无可播放剧集"
                                else if (requiredEpisodeNumber != null && resolved.lines.first().episodes.none {
                                    episodeNumber(it.title) == requiredEpisodeNumber
                                } && !(resolved.lines.first().episodes.all { episodeNumber(it.title) == null } &&
                                    requiredEpisodeNumber in 1..resolved.lines.first().episodes.size)) {
                                    failure = "该来源没有当前播放的第 $requiredEpisodeNumber 集，请选择其他来源"
                                } else onSelect(resolved)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                failure = error.message ?: "加载失败，请重试"
                            } finally {
                                selecting = false
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            TvActionButton("关闭", onClick = onDismiss)
        }
    }
}

@Composable
private fun PlaybackSourceCard(
    video: RemoteVideo,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(when {
                focused -> Color(0xFF314052)
                selected -> Color(0xFF392D1E)
                else -> Color(0xFF202B38)
            })
            .border(if (focused) 2.dp else 1.dp,
                when { focused -> Color.White; selected -> AccentGold; else -> Color.White.copy(alpha = .10f) }, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RemoteArtwork(video.posterUrl, video.title,
            Modifier.width(62.dp).height(86.dp).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(video.title, color = Color.White, fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(video.sourceName,
                modifier = Modifier.clip(RoundedCornerShape(5.dp))
                    .background(Color.White.copy(alpha = .08f)).padding(horizontal = 8.dp, vertical = 4.dp),
                color = if (selected) AccentGold else Color(0xFFD6DEE8), fontSize = 12.sp,
                fontWeight = FontWeight.Medium, maxLines = 1)
            Text(listOf(video.year, video.category).filter(String::isNotBlank).joinToString(" · "),
                color = ScreenMuted, fontSize = 12.sp, maxLines = 1)
        }
        Spacer(Modifier.width(16.dp))
        Text(if (selected) "✓  当前来源" else "›",
            color = if (selected || focused) AccentGold else ScreenMuted,
            fontSize = if (selected) 12.sp else 26.sp,
            fontWeight = FontWeight.SemiBold)
    }
}
