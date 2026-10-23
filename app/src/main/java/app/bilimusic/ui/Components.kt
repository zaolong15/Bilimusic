package app.bilimusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bilimusic.data.Song
import app.bilimusic.player.PlayerUi
import coil.compose.AsyncImage

fun fmtTime(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}

/** 说明性文字。对比度够用，所以用次要文字色，不用更浅的第三级灰。 */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun Cover(url: String, size: androidx.compose.ui.unit.Dp, radius: androidx.compose.ui.unit.Dp = RadiusSm) {
    AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(RoundedCornerShape(radius)).background(LocalTokens.current.bgSubtle)
    )
}

@Composable
fun SongRow(
    song: Song,
    current: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    menu: List<Pair<String, () -> Unit>>
) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Cover(song.cover, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                "${song.artist} · ${fmtTime(song.durationSec)}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onFavorite) {
            // 状态靠图标形状区分（实心/空心），不只靠颜色
            Icon(
                if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (favorite) "取消收藏" else "收藏",
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多操作") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                menu.forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
                }
            }
        }
    }
}

@Composable
fun SongList(
    songs: List<Song>,
    vm: AppViewModel,
    player: PlayerUi,
    onPlay: (Int) -> Unit,
    removeLabel: String? = null,
    onRemove: ((Song) -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null
) {
    val favIds by vm.favoriteIds.collectAsStateWithLifecycle()
    var addTarget by remember { mutableStateOf<Song?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        if (header != null) item { header() }
        items(songs.size, key = { songs[it].bvid }) { i ->
            val s = songs[i]
            val menu = buildList<Pair<String, () -> Unit>> {
                add("加入歌单" to { addTarget = s })
                if (removeLabel != null && onRemove != null) add(removeLabel to { onRemove(s) })
            }
            SongRow(
                song = s, current = player.currentId == s.bvid, favorite = s.bvid in favIds,
                onClick = { onPlay(i) }, onFavorite = { vm.toggleFavorite(s) }, menu = menu
            )
        }
        if (footer != null) item { footer() }
    }
    addTarget?.let { AddToPlaylistDialog(it, vm) { addTarget = null } }
}

@Composable
fun NameDialog(title: String, initial: String = "", confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text("歌单名称") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onConfirm(text.trim()) })
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun AddToPlaylistDialog(song: Song, vm: AppViewModel, onDismiss: () -> Unit) {
    val lists by vm.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog("新建歌单", confirm = "创建并加入", onDismiss = { creating = false },
            onConfirm = { vm.createPlaylist(it, song); onDismiss() })
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("加入歌单", style = MaterialTheme.typography.titleLarge) },
            text = {
                if (lists.isEmpty()) {
                    Text("还没有歌单。新建一个，把这首歌放进去。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn {
                        items(lists, key = { it.playlist.id }) { p ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .clickable { vm.addToPlaylist(p.playlist.id, p.playlist.name, song); onDismiss() },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(p.playlist.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${p.count} 首", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { creating = true }) { Text("新建歌单") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )
    }
}
