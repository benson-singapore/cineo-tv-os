package com.nova.tv

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import androidx.tv.material3.ExperimentalTvMaterial3Api

private val Gold = Color(0xFFE9B44C)
private val Muted = Color(0xFF969AA4)
private data class Movie(val title: String, val subtitle: String, val image: Int, val tag: String)
private data class NavDestination(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val destinations = listOf(
    NavDestination("首页", Icons.Filled.Home),
    NavDestination("电影", Icons.Filled.Movie),
    NavDestination("剧集", Icons.Filled.LiveTv),
    NavDestination("纪录片", Icons.Filled.Explore),
    NavDestination("我的收藏", Icons.Filled.Favorite)
)
private val movies = listOf(
    Movie("星际远航", "2025 · 科幻 / 冒险", R.drawable.space, "8.9"),
    Movie("长夜之后", "2024 · 悬疑 / 剧情", R.drawable.night, "8.6"),
    Movie("山野来信", "2025 · 冒险 / 纪录", R.drawable.mountain, "9.1"),
    Movie("无声轨道", "2024 · 科幻 / 剧情", R.drawable.space, "8.4"),
    Movie("最后的极光", "2025 · 剧情 / 冒险", R.drawable.night, "8.8"),
    Movie("远山之间", "2023 · 纪录 / 自然", R.drawable.mountain, "9.3")
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NovaHome() }
    }
}

@Composable
private fun FocusTile(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.onFocusChanged { focused = it.isFocused }
        .border(if (focused) 2.dp else 0.dp, if (focused) Gold else Color.Transparent, RoundedCornerShape(8.dp))
        .clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(3.dp)) { content() }
}

private fun Movie.tint() = when (image) {
    R.drawable.night -> Color(0xFF685086)
    R.drawable.mountain -> Color(0xFF3B7180)
    else -> Color(0xFF537C8D)
}

@Composable
private fun GlassButton(label: String, primary: Boolean = false, onClick: () -> Unit) {
    FocusTile(onClick = onClick) {
        Text(label, Modifier.background(if (primary) Color(0xFFE7EBE7) else Color.White.copy(alpha = .10f), RoundedCornerShape(7.dp)).padding(18.dp, 11.dp),
            color = if (primary) Color(0xFF172026) else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
@OptIn(ExperimentalTvMaterial3Api::class)
private fun NovaHome() {
    var section by remember { mutableStateOf("首页") }
    var selected by remember { mutableStateOf<Movie?>(null) }
    var featured by remember { mutableStateOf(movies.first()) }
    var filter by remember { mutableStateOf("全部") }
    var season by remember { mutableIntStateOf(1) }
    var episode by remember { mutableIntStateOf(1) }
    var preview by remember { mutableStateOf(false) }
    val favorites = remember { mutableStateListOf<String>() }
    val tint by animateColorAsState((selected ?: featured).tint(), label = "Artwork color")
    val firstAction = remember { FocusRequester() }
    LaunchedEffect(section, selected) { firstAction.requestFocus() }
    BackHandler(selected != null || section != "首页") {
        if (selected != null) selected = null else section = "首页"
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF090D13))) {
        Image(painterResource((selected ?: featured).image), null, Modifier.fillMaxSize().blur(85.dp), contentScale = ContentScale.Crop, alpha = .25f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint.copy(alpha = .22f), Color(0xF5090D13)))))
        Box(Modifier.fillMaxSize().padding(start = 84.dp)) {
            val movie = selected
            if (movie != null) {
                LazyColumn(contentPadding = PaddingValues(bottom = 35.dp)) {
                    item {
                        Hero(movie, "NOVA / ${if (section == "剧集") "SERIES" else "CINEMA"}", firstAction,
                            onPrimary = { preview = true },
                            primaryLabel = if (section == "剧集") "▶  预览第 $episode 集" else "▶  预告片信息",
                            onSecondary = { if (!favorites.remove(movie.title)) favorites.add(movie.title) },
                            secondaryLabel = if (movie.title in favorites) "✓  已收藏" else "＋  加入收藏")
                    }
                    item {
                        Row(Modifier.padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassButton("‹  返回列表") { selected = null }
                            Text("★ ${movie.tag}     4K UHD     Dolby Atmos", Modifier.padding(12.dp), color = Gold, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                    if (section == "剧集") {
                        item {
                            Row(Modifier.padding(horizontal = 32.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                (1..3).forEach { n -> GlassButton("第 $n 季", season == n) { season = n; episode = 1 } }
                            }
                            Spacer(Modifier.height(16.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = 29.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        Column(Modifier.padding(32.dp, 24.dp)) {
                            Text("关于影片", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))
                            Text("一个关于远行、选择与归属的故事。在未知的世界中，人们重新发现彼此的联系。精心呈现的画面与声音，带你走进另一种生活。", color = Muted, fontSize = 12.sp, lineHeight = 21.sp)
                            Spacer(Modifier.height(16.dp))
                            Text("导演  林远    /    主演  陈默 · 苏青 · 周予    /    语言  普通话    /    字幕  中文、英文", color = Muted, fontSize = 10.sp)
                        }
                    }
                    item { MovieShelf("你可能也喜欢", movies.filter { it != movie }, false) { selected = it } }
                }
            } else if (section == "首页") {
                LazyColumn(contentPadding = PaddingValues(bottom = 30.dp)) {
                    item { Hero(featured, "策展推荐 / EDITOR’S PICK", firstAction, { selected = featured }, "探索影片", { if (!favorites.remove(featured.title)) favorites.add(featured.title) }, if (featured.title in favorites) "✓  已收藏" else "＋  加入收藏") }
                    item {
                        Row(Modifier.padding(start = 32.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            movies.take(3).forEachIndexed { i, m -> GlassButton("0${i+1}  ${m.title}", featured == m) { featured = m } }
                        }
                    }
                    item { MovieShelf("接着上次的故事", movies.take(3), true) { selected = it } }
                    item { MovieShelf("最受欢迎", movies.take(5), false) { selected = it } }
                    item { MovieShelf("接着上次的故事", movies.take(3), true) { selected = it } }
                    item { MovieShelf("最新上线", movies.reversed(), false) { selected = it } }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(30.dp, 24.dp)) {
                    Text("YOUR PERSONAL CINEMA", color = tint.copy(red = .7f), fontSize = 9.sp, letterSpacing = 3.sp)
                    Spacer(Modifier.height(10.dp))
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
        // A fixed-width rail keeps the content aligned while moving through destinations.
        Column(
            Modifier.width(84.dp).fillMaxHeight()
                .background(Color(0xF0182029))
                .border(1.dp, Color.White.copy(alpha = .07f))
                .padding(horizontal = 12.dp, vertical = 24.dp)
                .focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("N", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(34.dp))
            destinations.forEach { destination ->
                FocusTile(
                    Modifier.size(54.dp).padding(bottom = 9.dp),
                    onClick = { section = destination.label; selected = null; filter = "全部" }
                ) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(
                                if (section == destination.label) Color.White.copy(alpha = .13f) else Color.Transparent,
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            destination.icon,
                            contentDescription = destination.label,
                            modifier = Modifier.size(23.dp),
                            tint = if (section == destination.label) Gold else Color(0xFFBBC2CD)
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("N", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
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
    }
}

@Composable
private fun Hero(movie: Movie, eyebrow: String, requester: FocusRequester, onPrimary: () -> Unit, primaryLabel: String, onSecondary: () -> Unit, secondaryLabel: String) {
    Box(Modifier.fillMaxWidth().height(460.dp)) {
        Image(painterResource(movie.image), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF0090D13), Color(0xA0090D13), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x33090D13), Color.Transparent, Color(0xFF090D13)))))
        Text("NOVA    /    COLLECTION 2025", Modifier.align(Alignment.TopEnd).padding(28.dp), color = Color.White.copy(alpha = .6f), fontSize = 9.sp, letterSpacing = 2.sp)
        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 36.dp, vertical = 24.dp).widthIn(max = 440.dp)) {
            Text(eyebrow, color = Gold, fontSize = 9.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(14.dp))
            Text(movie.title, color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text("BEYOND THE EVERYDAY", color = Color.White.copy(alpha = .55f), fontSize = 10.sp, letterSpacing = 5.sp)
            Spacer(Modifier.height(16.dp))
            Text("★ ${movie.tag}    ${movie.subtitle}    4K UHD", color = Color(0xFFD0D5DD), fontSize = 11.sp)
            Spacer(Modifier.height(12.dp))
            Text("向未知出发，在世界的另一端找回自己。\n一段值得放慢时间、沉浸其中的旅程。", color = Color(0xFFB4BDC8), fontSize = 12.sp, lineHeight = 21.sp, maxLines = 2)
            Spacer(Modifier.height(18.dp))
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color(0xFFE8E9EC), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("${entries.size} 部影片   ›", color = Muted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 30.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(entries) { movie ->
                Column(Modifier.width(if (continuing) 260.dp else 178.dp)) {
                    FocusTile(onClick = { onSelect(movie) }) {
                        Box(Modifier.fillMaxWidth().height(if (continuing) 142.dp else 248.dp).clip(RoundedCornerShape(9.dp))) {
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
