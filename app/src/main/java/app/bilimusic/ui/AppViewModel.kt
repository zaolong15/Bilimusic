package app.bilimusic.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.bilimusic.App
import app.bilimusic.data.*
import android.net.Uri
import app.bilimusic.player.PlayerUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val FAVORITES_ID = -1L
const val DOWNLOADS_ID = -2L

class PendingImport(val parsed: Parsed, val plan: Plan)

data class SearchState(
    val query: String = "",
    val results: List<Song> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val page: Int = 0,
    val canMore: Boolean = false
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as App
    private val api = graph.api
    private val session = graph.session
    private val dao = graph.db.dao()
    private val conn = graph.player
    private val dl = graph.downloader
    private val prefs = graph.prefs

    val player: StateFlow<PlayerUi> = conn.state
    val progress = conn.progress
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private fun <T> Flow<T>.eager(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val favoriteIds: StateFlow<Set<String>> = dao.favoriteIds().map { it.toSet() }.eager(emptySet())
    val playlists: StateFlow<List<PlaylistWithCount>> = dao.playlists().eager(emptyList())
    val favoriteCount: StateFlow<Int> = dao.favorites().map { it.size }.eager(0)

    // ---------- 资料库 ----------
    fun songsOf(id: Long): Flow<List<Song>> = when (id) {
        FAVORITES_ID -> dao.favorites()
        DOWNLOADS_ID -> dao.downloaded()
        else -> dao.playlistSongs(id)
    }
    fun nameOf(id: Long): Flow<String> = when (id) {
        FAVORITES_ID -> flowOf("我喜欢的")
        DOWNLOADS_ID -> flowOf("已下载")
        else -> dao.playlistName(id).map { it.orEmpty() }
    }

    /** 把整个列表放进播放队列，从 start 开始播放，播完自动接下一首。 */
    fun playQueue(songs: List<Song>, start: Int, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        viewModelScope.launch {
            songs.forEach { dao.ensureSong(it) }
            conn.playQueue(songs, start.coerceIn(songs.indices), shuffle)
        }
    }

    fun playAll(songs: List<Song>, shuffle: Boolean) =
        playQueue(songs, if (shuffle && songs.isNotEmpty()) songs.indices.random() else 0, shuffle)

    fun togglePlay() = conn.toggle()
    fun next() = conn.next()
    fun previous() = conn.previous()
    fun seekTo(ms: Long) = conn.seekTo(ms)
    fun cycleRepeat() = conn.cycleRepeat()
    fun toggleShuffle() = conn.toggleShuffle()

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            dao.ensureSong(song)
            val now = song.bvid in favoriteIds.value
            dao.setFavorite(song.bvid, !now, System.currentTimeMillis())
            messages.emit(if (now) "已取消收藏" else "已收藏")
        }
    }

    fun toggleFavoriteById(bvid: String?) {
        if (bvid == null) return
        viewModelScope.launch {
            val s = dao.getSong(bvid) ?: return@launch
            toggleFavorite(s)
        }
    }

    fun createPlaylist(name: String, thenAdd: Song? = null) {
        viewModelScope.launch {
            val id = dao.insertPlaylist(Playlist(name = name))
            if (thenAdd != null) {
                dao.ensureSong(thenAdd)
                dao.addToPlaylist(PlaylistSong(id, thenAdd.bvid))
                messages.emit("已加入新歌单「$name」")
            }
        }
    }

    fun addToPlaylist(pid: Long, name: String, song: Song) {
        viewModelScope.launch {
            dao.ensureSong(song)
            dao.addToPlaylist(PlaylistSong(pid, song.bvid))
            messages.emit("已加入「$name」")
        }
    }

    fun removeFromPlaylist(pid: Long, song: Song) {
        viewModelScope.launch {
            when (pid) {
                FAVORITES_ID -> dao.setFavorite(song.bvid, false, 0)
                DOWNLOADS_ID -> dl.remove(song.bvid)
                else -> dao.removeFromPlaylist(pid, song.bvid)
            }
        }
    }

    fun renamePlaylist(id: Long, name: String) { viewModelScope.launch { dao.renamePlaylist(id, name) } }
    fun deletePlaylist(id: Long) { viewModelScope.launch { dao.removePlaylist(id) } }

    // ---------- 外观 ----------
    val appearance = prefs.appearance
    fun setAppearance(f: (Appearance) -> Appearance) = prefs.update(f)

    // ---------- 下载 ----------
    val downloadProgress = dl.active
    val downloadedIds: StateFlow<Set<String>> = dao.downloadedIds().map { it.toSet() }.eager(emptySet())
    val downloadedCount: StateFlow<Int> = dao.downloaded().map { it.size }.eager(0)
    val storageBytes = MutableStateFlow(0L)

    fun refreshStorage() { viewModelScope.launch(Dispatchers.IO) { storageBytes.value = dl.usedBytes() } }

    fun download(songs: List<Song>) {
        val todo = songs.filter { it.bvid !in downloadedIds.value }
        if (todo.isEmpty()) { viewModelScope.launch { messages.emit("这些歌曲都已下载") }; return }
        dl.enqueue(todo)
        viewModelScope.launch { messages.emit("已加入下载队列，共 ${todo.size} 首") }
    }

    val wifiOnly = prefs.wifiOnly
    fun setWifiOnly(v: Boolean) = prefs.setWifiOnly(v)
    fun cancelDownload(bvid: String) = dl.cancel(bvid)

    fun removeDownload(song: Song) { viewModelScope.launch { dl.remove(song.bvid); refreshStorage() } }

    fun clearDownloads() {
        viewModelScope.launch { dl.clearAll(); refreshStorage(); messages.emit("已清除全部下载") }
    }

    // ---------- 导入 / 导出 ----------
    fun exportTo(uri: Uri, playlistId: Long?) {
        viewModelScope.launch {
            try {
                val text = Backup.export(dao, playlistId)
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                }
                messages.emit("已导出")
            } catch (e: Exception) { messages.emit("导出失败。${e.message.orEmpty()}") }
        }
    }

    /** 导入分两步：先读文件并生成预览，用户确认后才写入数据库。 */
    val pendingImport = MutableStateFlow<PendingImport?>(null)
    val linkImport = MutableStateFlow<String?>(null)

    fun prepareImport(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { Backup.readLimited(it) }
                }
                val parsed = Backup.parse(text)
                if (parsed.playlists.isEmpty() && parsed.favorites.isEmpty()) {
                    messages.emit("文件里没有歌单或收藏。")
                    return@launch
                }
                pendingImport.value = PendingImport(parsed, Backup.plan(dao, parsed))
            } catch (e: BackupException) { messages.emit(e.message.orEmpty()) }
            catch (e: Exception) { messages.emit("导入失败。无法读取这个文件。") }
        }
    }

    fun cancelImport() { pendingImport.value = null }

    fun confirmImport() {
        val p = pendingImport.value ?: return
        pendingImport.value = null
        viewModelScope.launch {
            try {
                val r = Backup.apply(graph.db, p.parsed)
                messages.emit("已导入：新建 ${r.created} 个歌单，并入 ${r.merged} 个，新增 ${r.newSongs} 首歌曲、${r.newFavorites} 首收藏")
            } catch (e: Exception) { messages.emit("导入失败。${e.message.orEmpty()}") }
        }
    }

    /** 从文本里找出 BV 号（支持完整链接、多行粘贴），逐个读取信息后建成歌单。 */
    fun importLinks(name: String, text: String) {
        val ids = Regex("BV[0-9A-Za-z]{10}").findAll(text).map { it.value }.distinct().take(200).toList()
        if (ids.isEmpty()) {
            viewModelScope.launch { messages.emit("没有找到 BV 号。请粘贴 BV 号或完整的视频链接（暂不支持 b23.tv 短链接）。") }
            return
        }
        viewModelScope.launch {
            val ok = ArrayList<Song>()
            var failed = 0
            var lastError: Exception? = null
            ids.forEachIndexed { i, id ->
                linkImport.value = "正在读取 ${i + 1}/${ids.size}…"
                try { ok += withContext(Dispatchers.IO) { api.videoInfo(id) } }
                catch (e: Exception) { failed++; lastError = e }
                delay(120)   // 放慢节奏，减少被风控的概率
            }
            linkImport.value = null
            if (ok.isEmpty()) {
                messages.emit("没有读取到任何歌曲。${lastError?.friendly().orEmpty()}")
                return@launch
            }
            val title = name.trim().ifBlank { "导入的歌单" }
            Backup.apply(graph.db, Parsed(listOf(Draft(title, ok)), emptyList()))
            messages.emit("已导入 ${ok.size} 首到「$title」" + if (failed > 0) "，$failed 条没能读取（可能已失效或无权限）" else "")
        }
    }

    // ---------- 搜索 ----------
    val search = MutableStateFlow(SearchState())

    fun runSearch(q: String, more: Boolean = false) {
        if (q.isBlank()) return
        viewModelScope.launch {
            val cur = search.value
            val page = if (more) cur.page + 1 else 1
            val base = if (more) cur.results else emptyList()
            search.value = cur.copy(query = q, loading = true, error = null, results = base)
            try {
                val r = withContext(Dispatchers.IO) { api.search(q, page) }
                search.value = search.value.copy(results = base + r, loading = false, page = page, canMore = r.isNotEmpty())
            } catch (e: Exception) {
                search.value = search.value.copy(loading = false, error = e.friendly())
            }
        }
    }

    // ---------- 账号 ----------
    val user = MutableStateFlow<UserInfo?>(null)
    val folders = MutableStateFlow<List<Folder>>(emptyList())
    val importing = MutableStateFlow<Long?>(null)

    init {
        refreshUser()
        viewModelScope.launch { conn.notices.collect { messages.emit(it) } }
        viewModelScope.launch { dl.failures.collect { messages.emit(it) } }
        viewModelScope.launch(Dispatchers.IO) { dl.reconcile() }
    }

    /** Cookie 还在但 B 站认为未登录 = 登录已过期，清掉并提示；单纯的网络错误不打扰用户。 */
    fun refreshUser() {
        if (session.cookie == null) return
        viewModelScope.launch {
            try {
                val me = withContext(Dispatchers.IO) { api.me() }
                if (me == null) {
                    session.clear()
                    user.value = null
                    messages.emit("登录已过期。请到「我的」重新登录。")
                } else user.value = me
            } catch (_: Exception) { }
        }
    }

    fun onLoginCookie(cookie: String) {
        session.save(cookie)
        refreshUser()
        viewModelScope.launch { messages.emit("登录成功") }
    }

    fun logout() {
        session.clear()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        user.value = null
        folders.value = emptyList()
    }

    fun loadFolders() {
        val mid = user.value?.mid ?: return
        viewModelScope.launch {
            try { folders.value = withContext(Dispatchers.IO) { api.folders(mid) } }
            catch (e: Exception) { messages.emit(e.friendly()) }
        }
    }

    fun importFolder(f: Folder) {
        viewModelScope.launch {
            importing.value = f.id
            try {
                val songs = ArrayList<Song>()
                var page = 1
                while (page <= 30) {
                    val (items, more) = withContext(Dispatchers.IO) { api.folderItems(f.id, page) }
                    songs += items
                    if (!more) break
                    page++
                }
                val pid = dao.insertPlaylist(Playlist(name = f.title))
                val now = System.currentTimeMillis()
                songs.forEachIndexed { i, s ->
                    dao.ensureSong(s)
                    dao.addToPlaylist(PlaylistSong(pid, s.bvid, now + i))
                }
                messages.emit("已导入「${f.title}」，共 ${songs.size} 首")
            } catch (e: Exception) {
                messages.emit(e.friendly())
            }
            importing.value = null
        }
    }
}
