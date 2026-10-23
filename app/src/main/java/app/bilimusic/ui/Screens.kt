package app.bilimusic.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

// ---------------------------------------------------------------- 资料库
@Composable
fun LibraryScreen(vm: AppViewModel, onOpen: (Long) -> Unit) {
    val lists by vm.playlists.collectAsStateWithLifecycle()
    val favCount by vm.favoriteCount.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTitle("资料库") }
        item { NavRow("我喜欢的", "$favCount 首") { onOpen(FAVORITES_ID) } }
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("歌单", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { creating = true }) { Text("新建歌单") }
            }
        }
        if (lists.isEmpty()) {
            item { Hint("还没有歌单。可以新建一个，或者在「我的」里把 B 站收藏夹导入为歌单。") }
        }
        items(lists, key = { it.playlist.id }) { p ->
            NavRow(p.playlist.name, "${p.count} 首") { onOpen(p.playlist.id) }
        }
    }
    if (creating) NameDialog("新建歌单", confirm = "创建", onDismiss = { creating = false }) {
        vm.createPlaylist(it); creating = false
    }
}

@Composable
private fun NavRow(title: String, meta: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = LocalTokens.current.borderSubtle)
    }
}

// ---------------------------------------------------------------- 歌单 / 我喜欢的
@Composable
fun ListScreen(vm: AppViewModel, id: Long, onBack: () -> Unit) {
    val songs by remember(id) { vm.songsOf(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val name by remember(id) { vm.nameOf(id) }.collectAsStateWithLifecycle(initialValue = "")
    val player by vm.player.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (id != FAVORITES_ID) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "歌单操作") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("删除歌单") }, onClick = { menu = false; deleting = true })
                    }
                }
            }
        }
        if (songs.isEmpty()) {
            Hint(if (id == FAVORITES_ID) "收藏的歌曲会出现在这里。在搜索结果里点心形图标即可收藏。"
                 else "歌单还是空的。在搜索结果里点「更多」，选择「加入歌单」。")
        }
        SongList(
            songs = songs, vm = vm, player = player,
            onPlay = { vm.playQueue(songs, it) },
            header = {
                if (songs.isNotEmpty()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.playAll(songs, shuffle = false) }, modifier = Modifier.heightIn(min = 44.dp)) { Text("播放全部") }
                        OutlinedButton(onClick = { vm.playAll(songs, shuffle = true) }, modifier = Modifier.heightIn(min = 44.dp)) { Text("随机播放") }
                    }
                }
            },
            removeLabel = if (id == FAVORITES_ID) "取消收藏" else "移出歌单",
            onRemove = { vm.removeFromPlaylist(id, it) }
        )
    }
    if (renaming) NameDialog("重命名歌单", initial = name, confirm = "保存", onDismiss = { renaming = false }) {
        vm.renamePlaylist(id, it); renaming = false
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("删除「$name」？", style = MaterialTheme.typography.titleLarge) },
        text = { Text("歌单会被删除，里面的歌曲不会从你的收藏中移除。") },
        confirmButton = {
            TextButton(onClick = { vm.deletePlaylist(id); deleting = false; onBack() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } }
    )
}

// ---------------------------------------------------------------- 搜索
@Composable
fun SearchScreen(vm: AppViewModel) {
    val st by vm.search.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf(st.query) }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("搜索")
        OutlinedTextField(
            value = q, onValueChange = { q = it }, label = { Text("在 B 站搜索音乐") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.runSearch(q) })
        )
        Spacer(Modifier.height(8.dp))
        when {
            st.error != null -> Text(st.error!!, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            st.loading && st.results.isEmpty() -> Hint("正在搜索…")
            st.results.isEmpty() -> Hint("输入歌名或歌手，搜索 B 站视频里的音乐。")
        }
        if (st.results.isNotEmpty()) {
            SongList(
                songs = st.results, vm = vm, player = player,
                onPlay = { vm.playQueue(st.results, it) },
                footer = {
                    if (st.canMore) TextButton(
                        onClick = { vm.runSearch(st.query, more = true) }, enabled = !st.loading,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) { Text(if (st.loading) "正在加载…" else "加载更多") }
                }
            )
        }
    }
}

// ---------------------------------------------------------------- 我的
@Composable
fun AccountScreen(vm: AppViewModel, onLogin: () -> Unit) {
    val user by vm.user.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    LaunchedEffect(user?.mid) { if (user != null) vm.loadFolders() }

    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTitle("我的") }
        val u = user
        if (u == null) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "登录哔哩哔哩后，可以播放账号有权限观看的内容，也能把 B 站收藏夹导入为歌单。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onLogin, modifier = Modifier.heightIn(min = 48.dp)) { Text("登录哔哩哔哩") }
                }
            }
        } else {
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = u.face, contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Text(u.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { vm.logout() }) { Text("退出登录") }
                }
            }
            item { Text("B 站收藏夹", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp)) }
            if (folders.isEmpty()) item { Hint("没有读取到收藏夹，或者正在加载。") }
            items(folders, key = { it.id }) { f ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text("${f.count} 个内容", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { vm.importFolder(f) }, enabled = importing == null,
                        modifier = Modifier.heightIn(min = 40.dp)) {
                        Text(if (importing == f.id) "导入中…" else "导入为歌单")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 登录（WebView）
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onCookie: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val done = remember { booleanArrayOf(false) }
    val latest by rememberUpdatedState(onCookie)
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
            Text("登录哔哩哔哩", style = MaterialTheme.typography.titleLarge)
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            val c = CookieManager.getInstance().getCookie("https://www.bilibili.com")
                            if (!done[0] && c != null && c.contains("SESSDATA=")) {
                                done[0] = true
                                latest(c)
                            }
                        }
                    }
                    loadUrl("https://passport.bilibili.com/h5-app/passport/login")
                }
            }
        )
    }
}
