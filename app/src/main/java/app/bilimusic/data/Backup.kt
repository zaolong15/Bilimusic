package app.bilimusic.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

class BackupException(message: String) : Exception(message)

/** 解析后的备份内容：先预览，用户确认后才写入。 */
data class Draft(val name: String, val songs: List<Song>)
data class Parsed(val playlists: List<Draft>, val favorites: List<Song>)

/** 预览数字：导入前告诉用户会发生什么。 */
data class Plan(val newLists: Int, val mergedLists: Int, val newSongs: Int, val knownSongs: Int, val newFavorites: Int)
data class ImportResult(val created: Int, val merged: Int, val newSongs: Int, val newFavorites: Int)

/** 歌单备份文件（JSON）。歌曲信息只存一份，歌单里按 BV 号引用。 */
object Backup {
    private const val APP = "BiliMusic"
    private const val VERSION = 1
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_PLAYLISTS = 500
    private const val MAX_SONGS = 20_000
    private val BVID = Regex("^BV[0-9A-Za-z]{10}$")

    fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw BackupException("文件太大，不像是歌单备份。")
            out.write(chunk, 0, n)
        }
        return out.toString("UTF-8")
    }

    // ---------------- 导出 ----------------
    /** onlyPlaylist 为 null 时导出全部歌单和收藏。 */
    suspend fun export(dao: LibraryDao, onlyPlaylist: Long?): String {
        val songs = LinkedHashMap<String, Song>()
        val playlists = JSONArray()
        val lists = if (onlyPlaylist != null) listOfNotNull(dao.playlistOnce(onlyPlaylist)) else dao.allPlaylistsOnce()
        for (p in lists) {
            val ids = JSONArray()
            for (s in dao.playlistSongsOnce(p.id)) { songs[s.bvid] = s; ids.put(s.bvid) }
            playlists.put(JSONObject().put("name", p.name).put("songs", ids))
        }
        val favs = JSONArray()
        if (onlyPlaylist == null) for (s in dao.favoritesOnce()) { songs[s.bvid] = s; favs.put(s.bvid) }

        val songArr = JSONArray()
        for (s in songs.values) {
            songArr.put(JSONObject().put("bvid", s.bvid).put("title", s.title).put("artist", s.artist)
                .put("cover", s.cover).put("durationSec", s.durationSec))
        }
        return JSONObject().put("app", APP).put("version", VERSION).put("exportedAt", System.currentTimeMillis())
            .put("songs", songArr).put("favorites", favs).put("playlists", playlists).toString(2)
    }

    // ---------------- 导入：解析 → 预览 → 写入 ----------------
    fun parse(text: String): Parsed {
        val root = try { JSONObject(text) } catch (e: Exception) { throw BackupException("这不是有效的歌单文件。") }
        if (root.optString("app") != APP) throw BackupException("这不是 BiliMusic 导出的歌单文件。")
        if (root.optInt("version", 0) > VERSION) throw BackupException("文件来自更新的版本。请先更新应用。")

        val byId = HashMap<String, Song>()
        val arr = root.optJSONArray("songs") ?: JSONArray()
        if (arr.length() > MAX_SONGS) throw BackupException("文件里的歌曲太多（超过 $MAX_SONGS 首）。")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("bvid")
            if (!BVID.matches(id)) continue   // 丢弃格式不对的条目
            byId[id] = Song(id, o.optString("title").take(300), o.optString("artist").take(100),
                o.optString("cover").take(500), o.optInt("durationSec").coerceAtLeast(0))
        }
        val lists = root.optJSONArray("playlists") ?: JSONArray()
        if (lists.length() > MAX_PLAYLISTS) throw BackupException("文件里的歌单太多（超过 $MAX_PLAYLISTS 个）。")
        val drafts = ArrayList<Draft>()
        for (i in 0 until lists.length()) {
            val o = lists.optJSONObject(i) ?: continue
            val ids = o.optJSONArray("songs") ?: JSONArray()
            val songs = (0 until ids.length()).mapNotNull { byId[ids.optString(it)] }
            drafts += Draft(o.optString("name").trim().ifBlank { "导入的歌单" }.take(60), songs)
        }
        val favIds = root.optJSONArray("favorites") ?: JSONArray()
        val favs = (0 until favIds.length()).mapNotNull { byId[favIds.optString(it)] }
        return Parsed(drafts, favs)
    }

    suspend fun plan(dao: LibraryDao, p: Parsed): Plan {
        val names = dao.allPlaylistNames().toMutableSet()
        var created = 0
        var merged = 0
        for (d in p.playlists) if (names.add(d.name)) created++ else merged++
        val known = dao.allBvids().toSet()
        val all = (p.playlists.flatMap { it.songs } + p.favorites).distinctBy { it.bvid }
        val favs = dao.favoritesOnce().map { it.bvid }.toSet()
        return Plan(created, merged, all.count { it.bvid !in known }, all.count { it.bvid in known },
            p.favorites.count { it.bvid !in favs })
    }

    /** 同名歌单会并入已有歌单（已有的歌曲自动跳过），不会产生重复的歌单。 */
    suspend fun apply(db: AppDb, p: Parsed): ImportResult {
        val dao = db.dao()
        val known = dao.allBvids().toMutableSet()
        var created = 0
        var merged = 0
        var newSongs = 0
        var newFavs = 0
        db.withTransaction {
            for (d in p.playlists) {
                val existing = dao.playlistIdByName(d.name)
                val pid = existing ?: dao.insertPlaylist(Playlist(name = d.name))
                if (existing == null) created++ else merged++
                val now = System.currentTimeMillis()
                d.songs.forEachIndexed { j, s ->
                    if (known.add(s.bvid)) newSongs++
                    dao.ensureSong(s)
                    dao.addToPlaylist(PlaylistSong(pid, s.bvid, now + j))
                }
            }
            val favs = dao.favoritesOnce().map { it.bvid }.toSet()
            val now = System.currentTimeMillis()
            p.favorites.forEachIndexed { i, s ->
                if (known.add(s.bvid)) newSongs++
                dao.ensureSong(s)
                if (s.bvid !in favs) { dao.setFavorite(s.bvid, true, now + i); newFavs++ }
            }
        }
        return ImportResult(created, merged, newSongs, newFavs)
    }
}
