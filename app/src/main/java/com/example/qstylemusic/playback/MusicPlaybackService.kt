package com.example.qstylemusic.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.qstylemusic.plugin.auth.NeteaseAccountClient
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * A background Media3 session shared by local songs and authenticated NetEase
 * playlist songs. Streaming URLs are resolved only as each media item is opened.
 */
@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val nativeClient = NeteaseAccountClient(applicationContext)
        val streamCache = mutableMapOf<String, Pair<Long, Uri>>()
        val factory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this)
        ) { spec ->
            val uri = spec.uri
            if (uri.scheme != "qstyle" || uri.host != "netease") {
                spec
            } else {
                val parts = uri.pathSegments
                val songId = parts.getOrNull(1).orEmpty()
                if (parts.size != 2 || parts[0] != "song" ||
                    !songId.matches(Regex("[0-9]{1,19}"))) {
                    throw IOException("无效的网易云歌曲媒体 URI")
                }
                // DataSource.resolveDataSpec runs on ExoPlayer's loader thread.
                // Cache briefly because this may be called again for HTTP range requests.
                val now = System.currentTimeMillis()
                val cached = synchronized(streamCache) {
                    streamCache[songId]?.takeIf { it.first > now }?.second
                }
                val playableUri = cached ?: try {
                    Uri.parse(runBlocking { nativeClient.streamUrl(songId) }).also { actual ->
                        synchronized(streamCache) {
                            streamCache[songId] =
                                (System.currentTimeMillis() + 120_000L) to actual
                        }
                    }
                } catch (e: Exception) {
                    throw IOException("网易云音频资源不可用：${e.localizedMessage.orEmpty()}", e)
                }
                spec.withUri(playableUri)
            }
        }

        val sourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = exoPlayer
        session = MediaSession.Builder(this, exoPlayer)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>
                ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(mediaItems)
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
