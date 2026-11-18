package com.example.qstylemusic.playback

import android.content.Context
import com.example.qstylemusic.plugin.MplugOnlineClient
import com.example.qstylemusic.plugin.MplugStore
import com.example.qstylemusic.plugin.OnlinePlayableTrack
import com.example.qstylemusic.plugin.auth.QqMusicAccountClient
import com.example.qstylemusic.plugin.auth.QqMusicLyricClient
import com.example.qstylemusic.plugin.auth.QqMusicLyricCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps only QQ playlist IDs/metadata. No expiring signed URLs are pre-fetched.
 * Previous/next explicitly re-resolves the newly selected track on demand.
 * This handles app controls; it does not replace a full Media3 queue/session implementation.
 */
class QqPlaylistNavigator(context: Context) {
    private val client = QqMusicAccountClient(context.applicationContext)
    private val lyricClient = QqMusicLyricClient(context.applicationContext)
    private val plugin = MplugOnlineClient(
        MplugStore(context.applicationContext), qqSession = client.session
    )
    private var tracks = emptyList<QqMusicAccountClient.Track>()
    private var currentIndex = -1
    private var busy = false

    fun start(queue: List<QqMusicAccountClient.Track>, index: Int) {
        if (index !in queue.indices) {
            clear()
            return
        }
        tracks = queue.toList()
        currentIndex = index
    }

    fun clear() {
        tracks = emptyList()
        currentIndex = -1
    }

    fun owns(mediaId: String?): Boolean = mediaId != null &&
        mediaId == tracks.getOrNull(currentIndex)?.let { "qqmusic:song:${it.mid}" }

    /** Null means success; a user-facing message means no switch occurred. */
    suspend fun move(
        direction: Int,
        currentMediaId: String?,
        wrap: Boolean,
        onPlay: (OnlinePlayableTrack) -> Unit
    ): String? {
        if (!owns(currentMediaId)) return "当前没有可切换的 QQ 音乐歌单"
        if (busy) return "正在获取歌曲，请稍候"
        if (direction != -1 && direction != 1) return "切换方向无效"
        var target = currentIndex + direction
        if (target !in tracks.indices) {
            if (!wrap || tracks.isEmpty()) {
                return if (direction > 0) "已经是歌单最后一首" else "已经是歌单第一首"
            }
            target = if (direction > 0) 0 else tracks.lastIndex
        }
        val song = tracks[target]
        busy = true
        return try {
            val url = client.streamUrl(song.mid, song.mediaMid)
            var lyric: String? = try {
                withTimeoutOrNull(5_000L) { lyricClient.lyrics(song.mid).lrc }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { null }
            if (lyric == null) {
                lyric = try {
                    withTimeoutOrNull(3_500L) {
                        plugin.lyrics("qqmusic.direct", song.mid, "https://u.y.qq.com")
                    }?.takeIf(QqMusicLyricCodec::hasTimedLines)
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { null }
            }
            onPlay(
                OnlinePlayableTrack(
                    mediaId = "qqmusic:song:${song.mid}",
                    title = song.name,
                    artist = song.artist,
                    artworkUrl = song.artworkUrl,
                    streamUrl = url,
                    lyrics = lyric
                )
            )
            currentIndex = target
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "切换歌曲失败：${e.localizedMessage.orEmpty().take(160)}"
        } finally {
            busy = false
        }
    }
}
