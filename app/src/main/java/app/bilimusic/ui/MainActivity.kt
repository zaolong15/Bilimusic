package app.bilimusic.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.media3.common.Player
import app.bilimusic.player.PlayerUi
import app.bilimusic.player.Progress

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { AppTheme { BiliMusicApp() } }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("library", "资料库", Icons.Filled.LibraryMusic),
    Tab("search", "搜索", Icons.Filled.Search),
    Tab("account", "我的", Icons.Filled.Person)
)

@Composable
fun BiliMusicApp(vm: AppViewModel = viewModel()) {
    val nav = rememberNavController()
    val player by vm.player.collectAsStateWithLifecycle()
    var nowPlaying by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val topLevel = route in tabs.map { it.route }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(if (topLevel) Modifier else Modifier.navigationBarsPadding()) {
                if (player.currentId != null) MiniPlayer(vm, player, onOpen = { nowPlaying = true })
                if (topLevel) {
                    HorizontalDivider(color = LocalTokens.current.borderSubtle)
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                        tabs.forEach { t ->
                            NavigationBarItem(
                                selected = route == t.route,
                                onClick = {
                                    nav.navigate(t.route) {
                                        popUpTo("library") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(t.label) }
                            )
                        }
                    }
                }
            }
        }
    ) { pad ->
        NavHost(nav, startDestination = "library", modifier = Modifier.padding(pad)) {
            composable("library") { LibraryScreen(vm) { nav.navigate("list/$it") } }
            composable("search") { SearchScreen(vm) }
            composable("account") { AccountScreen(vm) { nav.navigate("login") } }
            composable("list/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                ListScreen(vm, it.arguments!!.getLong("id")) { nav.popBackStack() }
            }
            composable("login") {
                LoginScreen(
                    onCookie = { c -> vm.onLoginCookie(c); nav.popBackStack() },
                    onBack = { nav.popBackStack() }
                )
            }
        }
    }

    // 正在播放：从下方淡入并上移，时长 240ms
    AnimatedVisibility(
        visible = nowPlaying,
        enter = fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 12 },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 12 }
    ) {
        BackHandler { nowPlaying = false }
        NowPlayingScreen(vm, player) { nowPlaying = false }
    }
}

@Composable
private fun MiniPlayer(vm: AppViewModel, p: PlayerUi, onOpen: () -> Unit) {
    val t = LocalTokens.current
    val prog by vm.progress.collectAsStateWithLifecycle()
    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        // 顶部 2dp 进度线：强调色只用于进度指示
        LinearProgressIndicator(
            progress = { if (prog.durationMs > 0) (prog.positionMs.toFloat() / prog.durationMs).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary, trackColor = t.borderSubtle, strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt
        )
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Cover(p.cover, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(
                    p.error ?: if (p.buffering) "正在缓冲…" else p.artist,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = if (p.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = vm::previous, enabled = p.hasPrev) { Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首") }
            IconButton(onClick = vm::togglePlay) {
                Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (p.playing) "暂停" else "播放")
            }
            IconButton(onClick = vm::next, enabled = p.hasNext) { Icon(Icons.Filled.SkipNext, contentDescription = "下一首") }
        }
    }
}

private fun fmtMs(ms: Long) = fmtTime((ms / 1000).toInt())

@Composable
private fun NowPlayingScreen(vm: AppViewModel, p: PlayerUi, onClose: () -> Unit) {
    val t = LocalTokens.current
    val favIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val prog by vm.progress.collectAsStateWithLifecycle()
    val fav = p.currentId in favIds
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    var drag by remember { mutableStateOf<Float?>(null) }
    val dur = prog.durationMs

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.toggleFavoriteById(p.currentId) }) {
                Icon(
                    if (fav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (fav) "取消收藏" else "收藏",
                    tint = if (fav) accent else dim
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Cover(p.cover, 280.dp, RadiusLg)
        Spacer(Modifier.height(32.dp))
        Text(p.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(p.artist, style = MaterialTheme.typography.bodyMedium, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        p.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))

        Slider(
            value = drag ?: if (dur > 0) (prog.positionMs.toFloat() / dur).coerceIn(0f, 1f) else 0f,
            onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let { f -> vm.seekTo((f * dur).toLong()) }; drag = null },
            enabled = dur > 0,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = t.bgInset)
        )
        Row(Modifier.fillMaxWidth()) {
            val shown = drag?.let { f -> (f * dur).toLong() } ?: prog.positionMs
            Text(fmtMs(shown), style = MaterialTheme.typography.bodySmall, color = dim)
            Spacer(Modifier.weight(1f))
            Text(fmtMs(dur), style = MaterialTheme.typography.bodySmall, color = dim)
        }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            // 开/关靠图标形状区分（带 On 的是开启态），不只靠颜色
            IconButton(onClick = vm::toggleShuffle, modifier = Modifier.size(48.dp)) {
                Icon(if (p.shuffle) Icons.Filled.ShuffleOn else Icons.Filled.Shuffle,
                    contentDescription = if (p.shuffle) "随机播放：开" else "随机播放：关", tint = if (p.shuffle) accent else dim)
            }
            IconButton(onClick = vm::previous, enabled = p.hasPrev, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首", modifier = Modifier.size(28.dp))
            }
            FilledIconButton(onClick = vm::togglePlay, modifier = Modifier.size(64.dp)) {
                Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (p.playing) "暂停" else "播放", modifier = Modifier.size(32.dp))
            }
            IconButton(onClick = vm::next, enabled = p.hasNext, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.SkipNext, contentDescription = "下一首", modifier = Modifier.size(28.dp))
            }
            IconButton(onClick = vm::cycleRepeat, modifier = Modifier.size(48.dp)) {
                when (p.repeat) {
                    Player.REPEAT_MODE_ONE -> Icon(Icons.Filled.RepeatOneOn, contentDescription = "单曲循环", tint = accent)
                    Player.REPEAT_MODE_ALL -> Icon(Icons.Filled.RepeatOn, contentDescription = "列表循环", tint = accent)
                    else -> Icon(Icons.Filled.Repeat, contentDescription = "顺序播放", tint = dim)
                }
            }
        }
        Spacer(Modifier.weight(1.2f))
    }
}
