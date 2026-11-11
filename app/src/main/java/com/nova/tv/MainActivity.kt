package com.nova.tv

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Theaters
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.tv.material3.Icon
import androidx.tv.material3.ExperimentalTvMaterial3Api

private val Gold = Color(0xFFE9B44C)
private val Muted = Color(0xFFF0F2F5)
private data class Movie(val title: String, val subtitle: String, val image: Int, val tag: String)
private data class NavDestination(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val section: String = label
)
private val destinations = listOf(
    NavDestination("主页", Icons.Filled.Home, "首页"),
    NavDestination("电影", Icons.Filled.Movie),
    NavDestination("剧集", Icons.Filled.LiveTv),
    NavDestination("动漫", Icons.Filled.Animation),
    NavDestination("短剧", Icons.Filled.Theaters),
    NavDestination("纪录片", Icons.Filled.Explore),
    NavDestination("继续观看", Icons.Filled.History),
    NavDestination("收藏", Icons.Filled.Favorite, "我的收藏"),
    NavDestination("搜索", Icons.Filled.Search)
)
private val settingsDestination = NavDestination("配置", Icons.Filled.Settings)
private val movies = listOf(
    Movie("星际远航", "2025 · 科幻 / 冒险", R.drawable.space, "8.9"),
    Movie("长夜之后", "2024 · 悬疑 / 剧情", R.drawable.night, "8.6"),
    Movie("山野来信", "2025 · 冒险 / 纪录", R.drawable.mountain, "9.1"),
    Movie("无声轨道", "2024 · 科幻 / 剧情", R.drawable.space, "8.4"),
    Movie("最后的极光", "2025 · 剧情 / 冒险", R.drawable.night, "8.8"),
    Movie("远山之间", "2023 · 纪录 / 自然", R.drawable.mountain, "9.3")
)

class MainActivity : ComponentActivity() {
    private val videoRepository by lazy { MacCmsRepository(applicationContext) }
    private val tmdbSettings by lazy { TmdbSettingsStore(applicationContext) }
    private val hubSyncEngine by lazy { HubSyncEngine(applicationContext, videoRepository, tmdbSettings) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NovaHome(videoRepository, hubSyncEngine, onExit = { finish() }) }
        lifecycleScope.launch { hubSyncEngine.pullAndApply(SyncTrigger.LAUNCH_PULL) }
    }

    override fun onResume() { super.onResume() }

    override fun onPause() { super.onPause() }

    override fun onStop() { super.onStop() }
}

@Composable
private fun FocusTile(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    focusColor: Color = Color.Transparent,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    onFocusChanged: (Boolean) -> Unit = {},
    content: @Composable () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val focusOutline = if (focused && focusColor != Color.Transparent) Modifier.border(2.dp, focusColor, shape) else Modifier
    Box(modifier.onFocusChanged {
        focused = it.isFocused
        onFocusChanged(it.isFocused)
    }.then(focusOutline)
        .clip(shape).clickable(onClick = onClick).padding(if (focused && focusColor != Color.Transparent) 2.dp else 3.dp)) { content() }
}

@Composable
private fun GlassButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(28.dp))
            .background(if (focused) Color(0xFFE7EBE7) else if (primary) Color.White.copy(alpha = .16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (focused) Color(0xFF172026) else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ExitChoiceButton(
    label: String,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    focused -> Color(0xFFE7EBE7)
                    destructive -> Color(0xFF65352F)
                    else -> Color(0xFF303B48)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (focused) Color(0xFF172026) else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
private fun NovaHome(videoRepository: MacCmsRepository, hubSyncEngine: HubSyncEngine, onExit: () -> Unit) {
    val context = LocalContext.current
    val menuPreferences = remember(context) { NavigationMenuPreferences(context.applicationContext) }
    val tmdbSettings = remember(context) { TmdbSettingsStore(context.applicationContext) }
    val tmdbRepository = remember(tmdbSettings) { TmdbMetadataRepository(tmdbSettings) }
    var menuSettings by remember { mutableStateOf(menuPreferences.load()) }
    val visibleDestinations = remember(menuSettings) {
        menuSettings.order
            .filter { it in menuSettings.visibleItems }
            .mapNotNull { label -> destinations.firstOrNull { it.label == label } }
    }
    var section by remember { mutableStateOf("首页") }
    val sectionNavigationDestination = remember(section, visibleDestinations) {
        if (section == "配置") null
        else visibleDestinations.firstOrNull { it.section == section } ?: visibleDestinations.firstOrNull()
    }
    var selected by remember { mutableStateOf<Movie?>(null) }
    var showExitConfirmation by remember { mutableStateOf(false) }
    var contentRevision by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("全部") }
    var season by remember { mutableIntStateOf(1) }
    var episode by remember { mutableIntStateOf(1) }
    var preview by remember { mutableStateOf(false) }
    var remoteDetail by remember { mutableStateOf<RemoteVideo?>(null) }
    var playbackEpisode by remember { mutableStateOf<PlaybackEpisode?>(null) }
    var playbackVideo by remember { mutableStateOf<RemoteVideo?>(null) }
    var playbackTitle by remember { mutableStateOf("") }
    var playbackLine by remember { mutableStateOf("") }
    var playbackEpisodes by remember { mutableStateOf<List<PlaybackEpisode>>(emptyList()) }
    var playbackStartPositionMs by remember { mutableLongStateOf(0L) }
    val playbackScope = rememberCoroutineScope()

    fun loadPlaybackEpisodes(video: RemoteVideo, lineName: String, currentEpisode: PlaybackEpisode) {
        playbackScope.launch {
            val fullEpisodeList = try {
                videoRepository.detail(video).lines.firstOrNull { it.name == lineName }?.episodes.orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            if (playbackVideo?.id == video.id && playbackLine == lineName && playbackEpisode?.url == currentEpisode.url && fullEpisodeList.isNotEmpty()) {
                playbackEpisodes = fullEpisodeList
            }
        }
    }
    val preserveHomeHeroPositionOnMenuEntry = remember { mutableStateOf(false) }
    val favorites = remember { mutableStateListOf<String>() }
    val firstAction = remember { FocusRequester() }
    val homeContentEntryFocus = remember { FocusRequester() }
    var lastHomeContentFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val sectionNavigationFocus = remember(section, visibleDestinations) { FocusRequester() }
    val sectionContentEntryFocus = remember(section) { FocusRequester() }
    val settingsMenuEntryFocus = remember { FocusRequester() }
    val detailEntryFocus = remember { FocusRequester() }
    var detailReturnFocusRequester by remember { mutableStateOf<FocusRequester?>(null) }
    var hadRemoteDetail by remember { mutableStateOf(false) }
    var hadPlayback by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        sectionNavigationFocus.requestFocus()
    }
    LaunchedEffect(remoteDetail?.id) {
        if (remoteDetail != null) {
            hadRemoteDetail = true
            runCatching { detailEntryFocus.requestFocus() }
        } else if (hadRemoteDetail) {
            hadRemoteDetail = false
            val restored = runCatching { detailReturnFocusRequester?.requestFocus() == true }.getOrDefault(false)
            if (!restored) runCatching { sectionNavigationFocus.requestFocus() }
            detailReturnFocusRequester = null
        }
    }
    LaunchedEffect(playbackEpisode) {
        if (playbackEpisode != null) {
            hadPlayback = true
        } else if (hadPlayback) {
            hadPlayback = false
            playbackScope.launch { hubSyncEngine.syncAfterPlaybackExit() }
            val requester = when {
                remoteDetail != null -> detailEntryFocus
                section == "首页" -> homeContentEntryFocus
                else -> sectionNavigationFocus
            }
            runCatching { requester.requestFocus() }
        }
    }
    LaunchedEffect(section, selected, remoteDetail, playbackEpisode) {
        if (section !in setOf("首页", "电影", "剧集", "纪录片", "动漫", "短剧", "继续观看", "我的收藏", "搜索", "配置") && remoteDetail == null && playbackEpisode == null) {
            firstAction.requestFocus()
        }
    }
    BackHandler {
        when {
            showExitConfirmation -> showExitConfirmation = false
            preview -> preview = false
            playbackEpisode != null -> {
                playbackEpisode = null
                playbackVideo = null
                playbackEpisodes = emptyList()
                playbackStartPositionMs = 0L
                contentRevision++
            }
            remoteDetail != null -> {
                remoteDetail = null
                contentRevision++
            }
            selected != null -> selected = null
            section != "首页" -> section = "首页"
            else -> showExitConfirmation = true
        }
    }
    CompositionLocalProvider(LocalTmdbMetadataRepository provides tmdbRepository) {
    Box(Modifier.fillMaxSize().background(Color(0xFF090D13))) {
        Box(
            Modifier.fillMaxSize()
                .then(if (remoteDetail != null || playbackEpisode != null) Modifier.focusProperties { canFocus = false } else Modifier)
        ) {
            val movie = selected
            when {
                section == "首页" -> VideoHomeScreen(
                    repository = videoRepository,
                    entryFocusRequester = homeContentEntryFocus,
                    onContentFocused = { lastHomeContentFocus = it },
                    menuFocusRequester = sectionNavigationFocus,
                    preserveHeroPositionOnMenuEntry = preserveHomeHeroPositionOnMenuEntry,
                    dataRevision = contentRevision,
                    onOpenDetail = { video, requester ->
                        detailReturnFocusRequester = requester
                        remoteDetail = video
                    },
                    onResume = { record ->
                        playbackVideo = record.video
                        playbackTitle = record.video.title
                        playbackLine = record.lineName
                        playbackEpisodes = listOf(record.episode)
                        playbackEpisode = record.episode
                        playbackStartPositionMs = if (record.isComplete) 0L else record.positionMs
                        loadPlaybackEpisodes(record.video, record.lineName, record.episode)
                    },
                    onOpenList = { section = it },
                    onOpenSection = { section = it },
                    onOpenConfig = { section = "配置" }
                )
                section == "搜索" -> VideoSearchScreen(
                    repository = videoRepository,
                    entryFocusRequester = sectionContentEntryFocus,
                    menuFocusRequester = sectionNavigationFocus,
                    onSelect = { video, requester ->
                        detailReturnFocusRequester = requester
                        remoteDetail = video
                    },
                    onOpenSources = { section = "配置" }
                )
                section == "配置" -> SettingsScreen(
                    repository = videoRepository,
                    tmdbSettings = tmdbSettings,
                    tmdbRepository = tmdbRepository,
                    hubSyncEngine = hubSyncEngine,
                    menuSettings = menuSettings,
                    entryFocusRequester = settingsMenuEntryFocus,
                    onMenuSettingsChange = { updated ->
                        menuPreferences.save(updated)
                        menuSettings = menuPreferences.load()
                    }
                )
                section in setOf("电影", "剧集", "纪录片", "动漫", "短剧") -> VideoCatalogScreen(
                    repository = videoRepository,
                    section = section,
                    entryFocusRequester = sectionContentEntryFocus,
                    menuFocusRequester = sectionNavigationFocus,
                    onSelect = { video, requester ->
                        detailReturnFocusRequester = requester
                        remoteDetail = video
                    },
                    onOpenConfig = { section = "配置" }
                )
                section in setOf("继续观看", "我的收藏") -> LocalVideoListScreen(
                    repository = videoRepository,
                    section = section,
                    dataRevision = contentRevision,
                    entryFocusRequester = sectionContentEntryFocus,
                    menuFocusRequester = sectionNavigationFocus,
                    onOpenDetail = { video, requester ->
                        detailReturnFocusRequester = requester
                        remoteDetail = video
                    },
                    onResume = { record ->
                        playbackVideo = record.video
                        playbackTitle = record.video.title
                        playbackLine = record.lineName
                        playbackEpisodes = listOf(record.episode)
                        playbackEpisode = record.episode
                        playbackStartPositionMs = record.positionMs
                        loadPlaybackEpisodes(record.video, record.lineName, record.episode)
                    }
                )
                movie != null -> {
                LazyColumn(contentPadding = PaddingValues(bottom = 35.dp)) {
                    item {
                        Hero(movie, firstAction,
                            onPrimary = { preview = true },
                            primaryLabel = if (section == "剧集") "▶  预览第 $episode 集" else "▶  预告片信息",
                            onSecondary = { if (!favorites.remove(movie.title)) favorites.add(movie.title) },
                            secondaryLabel = if (movie.title in favorites) "✓  已收藏" else "＋  加入收藏")
                    }
                    item {
                        Row(Modifier.padding(start = 110.dp, end = 30.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassButton("‹  返回列表") { selected = null }
                            Text("★ ${movie.tag}     4K UHD     Dolby Atmos", Modifier.padding(12.dp), color = Gold, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                    if (section == "剧集") {
                        item {
                            Row(Modifier.padding(start = 110.dp, end = 30.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                (1..3).forEach { n -> GlassButton("第 $n 季", season == n) { season = n; episode = 1 } }
                            }
                            Spacer(Modifier.height(16.dp))
                            LazyRow(contentPadding = PaddingValues(start = 110.dp, end = 30.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items((1..8).toList()) { n ->
                                    Column(Modifier.width(222.dp)) {
                                        FocusTile(onClick = { episode = n }) {
                                            Box(Modifier.height(110.dp).fillMaxWidth()) {
                                                Image(painterResource(movies[(n + season) % movies.size].image), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                                Text(if (episode == n) "● 当前选集" else "第 $n 集 · 46 分钟", Modifier.align(Alignment.BottomStart).background(Color(0xBB090D13)).padding(8.dp), color = Color.White, fontSize = 10.sp)
                                            }
                                        }
                                        Text("S0$season · E0$n  ${listOf("信号", "回声", "边界", "重逢")[(n-1)%4]}", Modifier.padding(5.dp), color = Color.White, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Column(Modifier.padding(start = 110.dp, top = 24.dp, end = 30.dp, bottom = 30.dp)) {
                            Text("关于影片", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))
                            Text("一个关于远行、选择与归属的故事。在未知的世界中，人们重新发现彼此的联系。精心呈现的画面与声音，带你走进另一种生活。", color = Muted, fontSize = 12.sp, lineHeight = 21.sp)
                            Spacer(Modifier.height(16.dp))
                            Text("导演  林远    /    主演  陈默 · 苏青 · 周予    /    语言  普通话    /    字幕  中文、英文", color = Muted, fontSize = 10.sp)
                        }
                    }
                    item { MovieShelf("你可能也喜欢", movies.filter { it != movie }, false) { selected = it } }
                }
                }
                else -> {
                Column(Modifier.fillMaxSize().padding(start = 110.dp, top = 24.dp, end = 30.dp, bottom = 30.dp)) {
                    Text(section, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(if (section == "剧集") "追随每一个故事，直到下一集。" else "收藏每一次值得沉浸的时刻。", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("全部", "科幻", "剧情", "冒险", "纪录").forEachIndexed { i, name ->
                            Box(if (i == 0) Modifier.focusRequester(firstAction) else Modifier) { GlassButton(name, filter == name) { filter = name } }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    val entries = movies.filter { (filter == "全部" || it.subtitle.contains(filter)) && (section != "我的收藏" || it.title in favorites) }
                    if (entries.isEmpty()) Text("还没有收藏，去影片详情页加入喜欢的故事吧。", color = Muted, fontSize = 14.sp)
                    LazyVerticalGrid(GridCells.Adaptive(145.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(entries) { m ->
                            Column {
                                FocusTile(onClick = { selected = m }) {
                                    Box(Modifier.height(192.dp).fillMaxWidth()) {
                                        Image(painterResource(m.image), m.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEF090D13)))))
                                        Text(m.title, Modifier.align(Alignment.BottomStart).padding(13.dp), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                        Text("★ ${m.tag}", Modifier.align(Alignment.TopEnd).padding(9.dp), color = Gold, fontSize = 10.sp)
                                    }
                                }
                                Text(m.title, Modifier.padding(5.dp), color = Color.White, fontSize = 12.sp)
                                Text(if (section == "剧集") "3 季 · 每季 8 集" else m.subtitle, Modifier.padding(start = 5.dp), color = Muted, fontSize = 10.sp)
                            }
                        }
                    }
                }
                }
            }
        }
        if (remoteDetail != null) {
            Box(
                Modifier.fillMaxSize()
                    .then(if (playbackEpisode != null) Modifier.focusProperties { canFocus = false } else Modifier)
            ) {
                RemoteVideoDetailScreen(
                    repository = videoRepository,
                    tmdbSettings = tmdbSettings,
                    tmdbRepository = tmdbRepository,
                    hubSyncEngine = hubSyncEngine,
                    entryFocusRequester = detailEntryFocus,
                    initial = remoteDetail!!,
                    onPlay = { video, line, episode ->
                        playbackVideo = video
                        playbackTitle = video.title
                        playbackLine = line.name
                        playbackEpisodes = line.episodes
                        playbackStartPositionMs = videoRepository.watchRecord(video)
                            ?.takeIf { !it.isComplete && matchingHistoryEpisode(it, line.episodes)?.url == episode.url }
                            ?.positionMs ?: 0L
                        playbackEpisode = episode
                    }
                )
            }
        }
        if (playbackEpisode != null && playbackVideo != null) {
            TvVideoPlayerScreen(
                repository = videoRepository,
                video = playbackVideo!!,
                episode = playbackEpisode!!,
                videoTitle = playbackTitle,
                lineName = playbackLine,
                episodes = playbackEpisodes,
                startPositionMs = playbackStartPositionMs,
                onSwitchSource = { sourceVideo, sourceLine, sourceEpisode, position ->
                    playbackVideo = sourceVideo
                    playbackTitle = sourceVideo.title
                    playbackLine = sourceLine.name
                    playbackEpisodes = sourceLine.episodes
                    playbackStartPositionMs = position
                    playbackEpisode = sourceEpisode
                },
                onExit = {
                    playbackScope.launch { hubSyncEngine.syncAfterPlaybackExit() }
                    playbackEpisode = null
                    playbackVideo = null
                    playbackEpisodes = emptyList()
                    playbackStartPositionMs = 0L
                    contentRevision++
                },
                onSelectEpisode = { selectedEpisode ->
                    playbackStartPositionMs = 0L
                    playbackEpisode = selectedEpisode
                },
                onProgress = { position, duration ->
                    videoRepository.saveWatchProgress(
                        playbackVideo!!, playbackLine, playbackEpisode!!, position, duration
                    )
                    playbackScope.launch { hubSyncEngine.syncProgressIfDue() }
                },
                onPause = { playbackScope.launch { hubSyncEngine.syncAfterPlaybackPause() } }
            )
        }
        // Navigation floats over the artwork without an opaque panel or divider.
        if (playbackEpisode == null) Column(
            Modifier.width(68.dp).fillMaxHeight()
                .padding(horizontal = 9.dp, vertical = 20.dp)
                .then(when {
                    remoteDetail != null -> Modifier.focusProperties { right = detailEntryFocus }
                    section == "首页" -> Modifier.focusProperties { right = homeContentEntryFocus }
                    section == "配置" -> Modifier.focusProperties { right = settingsMenuEntryFocus }
                    else -> Modifier
                })
                .focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(painterResource(R.mipmap.ic_launcher), "Cineo logo", Modifier.size(30.dp).clip(CircleShape))
            Spacer(Modifier.height(26.dp))
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(bottom = 5.dp)
            ) {
                items(visibleDestinations, key = { it.label }) { destination ->
                    var navFocused by remember(destination.label) { mutableStateOf(false) }
                    val isCurrent = section == destination.section
                    val navShape = RoundedCornerShape(15.dp)
                    val contentEntryFocus = when {
                        remoteDetail != null -> detailEntryFocus
                        section == "首页" -> homeContentEntryFocus
                        section == "配置" -> settingsMenuEntryFocus
                        else -> sectionContentEntryFocus
                    }
                    val contentRightKey = contentEntryFocus.let { requester -> Modifier.onKeyEvent { event ->
                        if (event.key == Key.DirectionRight && event.type == KeyEventType.KeyDown) {
                            if (section == "首页" && remoteDetail == null) {
                                val restored = runCatching { lastHomeContentFocus?.requestFocus() == true }.getOrDefault(false)
                                if (restored) return@onKeyEvent true
                                preserveHomeHeroPositionOnMenuEntry.value = true
                            }
                            runCatching { requester.requestFocus() }.getOrDefault(false)
                        } else false
                    } }
                    FocusTile(
                        Modifier.size(44.dp).padding(bottom = 5.dp)
                            .then(if (destination == sectionNavigationDestination) Modifier.focusRequester(sectionNavigationFocus) else Modifier)
                            .then(contentRightKey),
                        onClick = {
                            if (remoteDetail != null) contentRevision++
                            if (remoteDetail != null) detailReturnFocusRequester = null
                            section = destination.section
                            selected = null
                            remoteDetail = null
                            filter = "全部"
                        },
                        focusColor = Color.White,
                        shape = navShape,
                        onFocusChanged = { navFocused = it }
                    ) {
                        Box(
                            Modifier.fillMaxSize()
                                .background(
                                    when {
                                        navFocused -> Color.White.copy(alpha = .24f)
                                        isCurrent -> Color.White.copy(alpha = .13f)
                                        else -> Color.Transparent
                                    },
                                    navShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                destination.icon,
                                contentDescription = destination.label,
                                modifier = Modifier.size(20.dp),
                                tint = if (isCurrent || navFocused) Gold else Color(0xFFBBC2CD)
                            )
                        }
                    }
                }
            }
            var settingsFocused by remember { mutableStateOf(false) }
            val settingsShape = RoundedCornerShape(15.dp)
            val settingsRightKey = if (section == "配置") Modifier.onKeyEvent { event ->
                if (event.key == Key.DirectionRight && event.type == KeyEventType.KeyDown) {
                    settingsMenuEntryFocus.requestFocus()
                    true
                } else false
            } else Modifier
            FocusTile(
                Modifier.size(44.dp).padding(bottom = 5.dp).then(settingsRightKey),
                onClick = { section = settingsDestination.label; selected = null; remoteDetail = null },
                focusColor = Color.White,
                shape = settingsShape,
                onFocusChanged = { settingsFocused = it }
            ) {
                Box(
                    Modifier.fillMaxSize().background(
                        when {
                            settingsFocused -> Color.White.copy(alpha = .24f)
                            section == settingsDestination.label -> Color.White.copy(alpha = .13f)
                            else -> Color.Transparent
                        }, settingsShape
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        settingsDestination.icon,
                        contentDescription = settingsDestination.label,
                        modifier = Modifier.size(20.dp),
                        tint = if (section == settingsDestination.label || settingsFocused) Gold else Color(0xFFBBC2CD)
                    )
                }
            }
        }
        if (preview) androidx.compose.ui.window.Dialog(onDismissRequest = { preview = false }) {
            Column(Modifier.width(420.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xF0202935)).border(1.dp, Color.White.copy(alpha = .15f), RoundedCornerShape(18.dp)).padding(28.dp)) {
                Text(selected?.title ?: "影片预览", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Text(if (section == "剧集") "第 $season 季 · 第 $episode 集 · 46 分钟" else "官方预告 · 2 分 18 秒", color = Gold, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Text("当前为界面演示，尚未连接视频源。", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(20.dp))
                GlassButton("返回详情", true) { preview = false }
            }
        }
        if (showExitConfirmation) androidx.compose.ui.window.Dialog(
            onDismissRequest = { showExitConfirmation = false }
        ) {
            val exitFocusRequester = remember { FocusRequester() }
            val continueFocusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { continueFocusRequester.requestFocus() }

            Column(
                Modifier.width(420.dp)
                    .focusGroup()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xF0202935))
                    .border(1.dp, Color.White.copy(alpha = .15f), RoundedCornerShape(18.dp))
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("退出 Cineo TV？", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text("确认后将关闭应用。", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(22.dp))
                ExitChoiceButton(
                    label = "确认退出",
                    destructive = true,
                    modifier = Modifier.focusRequester(exitFocusRequester),
                    onClick = onExit
                )
                Spacer(Modifier.height(10.dp))
                ExitChoiceButton(
                    label = "继续观看",
                    modifier = Modifier.focusRequester(continueFocusRequester),
                    onClick = { showExitConfirmation = false }
                )
            }
        }
    }
}
}

@Composable
private fun Hero(movie: Movie, requester: FocusRequester, onPrimary: () -> Unit, primaryLabel: String, onSecondary: () -> Unit, secondaryLabel: String) {
    Box(Modifier.fillMaxWidth().height(350.dp)) {
        Image(
            painterResource(movie.image),
            null,
            Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize()
                .background(Brush.horizontalGradient(colorStops = arrayOf(
                    0f to Color(0xE6090D13),
                    .18f to Color(0xAA090D13),
                    .56f to Color.Transparent
                )))
        )
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(colorStops = arrayOf(
                0f to Color.Transparent,
                .38f to Color(0x33090D13),
                .68f to Color(0xB3090D13),
                1f to Color(0xFF090D13)
            )))
        )
        Column(Modifier.align(Alignment.CenterStart).padding(start = 110.dp, end = 36.dp, top = 10.dp, bottom = 10.dp).widthIn(max = 440.dp)) {
            Spacer(Modifier.height(8.dp))
            Text(movie.title, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text("BEYOND THE EVERYDAY", color = Color.White.copy(alpha = .82f), fontSize = 10.sp, letterSpacing = 5.sp)
            Spacer(Modifier.height(10.dp))
            Text("★ ${movie.tag}    ${movie.subtitle}    4K UHD", color = Color(0xFFF0F2F5), fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Text("向未知出发，在世界的另一端找回自己。\n一段值得放慢时间、沉浸其中的旅程。", color = Color(0xFFE2E6EB), fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.focusRequester(requester)) { GlassButton(primaryLabel, true, onPrimary) }
                GlassButton(secondaryLabel, false, onSecondary)
            }
        }
    }
}

@Composable
private fun MovieShelf(title: String, entries: List<Movie>, continuing: Boolean, onSelect: (Movie) -> Unit) {
    Column(Modifier.padding(top = 9.dp, bottom = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 110.dp, end = 30.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color(0xFFE8E9EC), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("${entries.size} 部影片   ›", color = Muted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(start = 110.dp, end = 30.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(entries) { movie ->
                Column(Modifier.width(if (continuing) 220.dp else 136.dp)) {
                    FocusTile(onClick = { onSelect(movie) }, focusColor = Color.White, shape = RoundedCornerShape(12.dp)) {
                        Box(Modifier.fillMaxWidth().height(if (continuing) 112.dp else 204.dp).clip(RoundedCornerShape(9.dp))) {
                            Image(painterResource(movie.image), movie.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDF101217)))))
                            Text(movie.title, Modifier.align(Alignment.BottomStart).padding(14.dp, 18.dp), color = Color.White, fontSize = if (continuing) 16.sp else 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(if (continuing) "还剩 42 分钟" else "★ ${movie.tag}", Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0xAA101217), RoundedCornerShape(3.dp)).padding(5.dp, 3.dp), color = Gold, fontSize = 8.sp)
                            if (continuing) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.64f).height(3.dp).background(Gold))
                        }
                    }
                    Text(movie.title, Modifier.padding(start = 4.dp, top = 6.dp), color = Color(0xFFE0E1E5), fontSize = 11.sp)
                    Text(movie.subtitle, Modifier.padding(start = 4.dp, top = 3.dp), color = Muted, fontSize = 9.sp)
                }
            }
        }
    }
}
