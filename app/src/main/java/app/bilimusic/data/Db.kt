package app.bilimusic.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "songs")
data class Song(
    @PrimaryKey val bvid: String,
    val title: String,
    val artist: String,
    val cover: String,
    val durationSec: Int,
    val favorite: Boolean = false,
    val favAt: Long = 0
)

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlist_songs", primaryKeys = ["playlistId", "bvid"])
data class PlaylistSong(
    val playlistId: Long,
    val bvid: String,
    val addedAt: Long = System.currentTimeMillis()
)

data class PlaylistWithCount(
    @Embedded val playlist: Playlist,
    val count: Int
)

@Dao
interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongIfAbsent(song: Song)

    @Query("UPDATE songs SET title=:title, artist=:artist, cover=:cover, durationSec=:dur WHERE bvid=:bvid")
    suspend fun updateMeta(bvid: String, title: String, artist: String, cover: String, dur: Int)

    @Query("SELECT * FROM songs WHERE bvid=:bvid")
    suspend fun getSong(bvid: String): Song?

    @Query("UPDATE songs SET favorite=:fav, favAt=:at WHERE bvid=:bvid")
    suspend fun setFavorite(bvid: String, fav: Boolean, at: Long)

    @Query("SELECT * FROM songs WHERE favorite=1 ORDER BY favAt DESC")
    fun favorites(): Flow<List<Song>>

    @Query("SELECT bvid FROM songs WHERE favorite=1")
    fun favoriteIds(): Flow<List<String>>

    @Query("SELECT p.*, (SELECT COUNT(*) FROM playlist_songs WHERE playlistId=p.id) AS count FROM playlists p ORDER BY p.createdAt DESC")
    fun playlists(): Flow<List<PlaylistWithCount>>

    @Query("SELECT name FROM playlists WHERE id=:id")
    fun playlistName(id: Long): Flow<String?>

    @Insert
    suspend fun insertPlaylist(p: Playlist): Long

    @Query("UPDATE playlists SET name=:name WHERE id=:id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id=:id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId=:id")
    suspend fun clearPlaylist(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addToPlaylist(ps: PlaylistSong)

    @Query("DELETE FROM playlist_songs WHERE playlistId=:pid AND bvid=:bvid")
    suspend fun removeFromPlaylist(pid: Long, bvid: String)

    @Query("SELECT s.* FROM songs s JOIN playlist_songs ps ON s.bvid=ps.bvid WHERE ps.playlistId=:pid ORDER BY ps.addedAt ASC")
    fun playlistSongs(pid: Long): Flow<List<Song>>
}

suspend fun LibraryDao.ensureSong(s: Song) {
    insertSongIfAbsent(s)
    updateMeta(s.bvid, s.title, s.artist, s.cover, s.durationSec)
}

suspend fun LibraryDao.removePlaylist(id: Long) {
    clearPlaylist(id)
    deletePlaylist(id)
}

@Database(entities = [Song::class, Playlist::class, PlaylistSong::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): LibraryDao
}
