package app.bilimusic.player

import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.bilimusic.App
import app.bilimusic.data.PlaybackNotes
import app.bilimusic.data.REFERER
import app.bilimusic.data.UA
import app.bilimusic.data.friendly
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** 前台媒体服务：锁屏、通知栏、耳机按键、后台播放都由它负责。 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val api = (application as App).api

        // 播放时才解析直链，避免队列里的链接过期。
        val dataSources = ResolvingDataSource.Factory(
            DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
                .setDefaultRequestProperties(mapOf("Referer" to REFERER))
        ) { spec ->
            if (spec.uri.scheme == "bili") {
                try {
                    val url = api.audioUrl(spec.uri.lastPathSegment.orEmpty())
                    PlaybackNotes.last = null
                    spec.withUri(Uri.parse(url))
                } catch (e: Exception) {
                    PlaybackNotes.last = e.friendly()
                    throw e
                }
            } else spec
        }

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSources))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.repeatMode = Player.REPEAT_MODE_ALL

        session = MediaSession.Builder(this, player)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> {
                    val items = mediaItems.map {
                        it.buildUpon().setUri("bili://play/${it.mediaId}").build()
                    }.toMutableList()
                    return Futures.immediateFuture(items)
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
