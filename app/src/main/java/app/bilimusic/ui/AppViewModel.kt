package app.bilimusic.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.bilimusic.App
import app.bilimusic.data.*
import app.bilimusic.player.PlayerUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val FAVORITES_ID = -1L

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

    val player: StateFlow<PlayerUi> = conn.state
    val progress = conn.progress
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private fun <T> Flow<T>.eager(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val favoriteIds: StateFlow<Set<String>> = dao.favoriteIds().map { it.toSet() }.eager(emptySet())
    val playlists: StateFlow<List<PlaylistWithCount>> = dao.playlists().eager(emptyList())
    val favoriteCount: StateFlow<Int> = dao.favorites().map { it.size }.eager(0)

    // ---------- 资料库 ----------
    fun songsOf(id: Long): Flow<List<Song>> = if (id == FAVORITES_ID) dao.favorites() else dao.playlistSongs(id)
    fun nameOf(id: Long): Flow<String> =
        if (id == FAVORITES_ID) flowOf("我喜欢的") else dao.playlistName(id).map { it.orEmpty() }

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
            if (pid == FAVORITES_ID) dao.setFavorite(song.bvid, false, 0)
            else dao.removeFromPlaylist(pid, song.bvid)
        }
    }

    fun renamePlaylist(id: Long, name: String) { viewModelScope.launch { dao.renamePlaylist(id, name) } }
    fun deletePlaylist(id: Long) { viewModelScope.launch { dao.removePlaylist(id) } }

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
