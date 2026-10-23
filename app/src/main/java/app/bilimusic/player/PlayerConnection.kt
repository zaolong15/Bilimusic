package app.bilimusic.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.bilimusic.data.PlaybackNotes
import app.bilimusic.data.Song
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PlayerUi(
    val currentId: String? = null,
    val title: String = "",
    val artist: String = "",
    val cover: String = "",
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val error: String? = null,
    val repeat: Int = Player.REPEAT_MODE_ALL,
    val shuffle: Boolean = false,
    val hasNext: Boolean = false,
    val hasPrev: Boolean = false
)

/** 进度单独成流：每 500ms 刷新，只有迷你播放器和播放页订阅，列表不会跟着重组。 */
data class Progress(val positionMs: Long = 0, val durationMs: Long = 0)

fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(bvid)
    .setUri("bili://play/$bvid")
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setArtworkUri(Uri.parse(cover))
            .build()
    )
    .build()

/** 界面与 PlaybackService 之间的桥。 */
class PlayerConnection(private val ctx: Context) {
    private val _state = MutableStateFlow(PlayerUi())
    val state: StateFlow<PlayerUi> = _state
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress
    /** 需要告诉用户的一次性消息（例如已自动跳过坏曲目）。 */
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var controller: MediaController? = null
    private val waiting = ArrayList<(MediaController) -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private var errorStreak = 0

    private val tick = object : Runnable {
        override fun run() {
            val c = controller ?: return
            _progress.value = Progress(c.currentPosition.coerceAtLeast(0), c.duration.coerceAtLeast(0))
            if (c.isPlaying) handler.postDelayed(this, 500)
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) errorStreak = 0
        }

        override fun onPlayerError(error: PlaybackException) {
            val reason = PlaybackNotes.last ?: (error.cause?.message ?: error.message ?: "未知错误")
            val c = controller
            errorStreak++
            // 连续失败不超过 3 次时自动跳到下一首，避免断网时在队列里空转
            if (c != null && errorStreak <= 3 && c.hasNextMediaItem()) {
                c.seekToNextMediaItem(); c.prepare(); c.play()
                notices.tryEmit("已跳过无法播放的曲目。$reason")
            } else {
                _state.value = _state.value.copy(playing = false, error = "无法播放。$reason")
            }
        }
    }

    init {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(listener)
            sync(c)
            waiting.forEach { it(c) }
            waiting.clear()
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun sync(p: Player) {
        val m = p.mediaMetadata
        _state.value = PlayerUi(
            currentId = p.currentMediaItem?.mediaId,
            title = m.title?.toString().orEmpty(),
            artist = m.artist?.toString().orEmpty(),
            cover = m.artworkUri?.toString().orEmpty(),
            playing = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            error = if (p.playerError == null) null else _state.value.error,
            repeat = p.repeatMode,
            shuffle = p.shuffleModeEnabled,
            hasNext = p.hasNextMediaItem(),
            hasPrev = p.hasPreviousMediaItem()
        )
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else waiting.add(block)
    }

    fun playQueue(songs: List<Song>, start: Int, shuffle: Boolean) = withController {
        it.shuffleModeEnabled = shuffle
        it.setMediaItems(songs.map { s -> s.toMediaItem() }, start, 0L)
        it.prepare()
        it.play()
    }

    fun toggle() = withController {
        when {
            it.isPlaying -> it.pause()
            it.playbackState == Player.STATE_ENDED -> { it.seekTo(0); it.play() }
            else -> { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() }
        }
    }

    fun next() = withController { it.seekToNextMediaItem() }

    /** 播放超过 3 秒时「上一首」先回到开头，和大多数播放器一致。 */
    fun previous() = withController { it.seekToPrevious() }

    fun seekTo(ms: Long) = withController { it.seekTo(ms) }

    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_ALL
        }
    }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
}
