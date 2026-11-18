package com.example.qstylemusic.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.qstylemusic.plugin.MplugOnlineClient
import com.example.qstylemusic.plugin.MplugStore
import com.example.qstylemusic.plugin.OnlinePlayableTrack
import com.example.qstylemusic.plugin.auth.QqMusicAccountClient
import com.example.qstylemusic.plugin.auth.QqMusicLyricClient
import com.example.qstylemusic.plugin.auth.QqMusicLyricCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Direct QQ playlist details: card -> this full-screen screen -> track.
 * Does not go through the account's separate QQ playlist list.
 * Uses the unchanged V2.2.4 stream resolver and V2.2.5 lyric fetcher.
 */
@Composable
fun QqMusicPlaylistDetailScreen(
    playlist: QqMusicAccountClient.Playlist,
    onBack: () -> Unit,
    onPlayTrack: (OnlinePlayableTrack) -> Unit,
    onQueueReady: ((List<QqMusicAccountClient.Track>, Int) -> Unit)? = null,
    showMiniPlayer: Boolean = false
) {
    val context = LocalContext.current
    val client = remember(context) { QqMusicAccountClient(context.applicationContext) }
    val lyricClient = remember(context) { QqMusicLyricClient(context.applicationContext) }
    val pluginClient = remember(context) {
        MplugOnlineClient(MplugStore(context.applicationContext), qqSession = client.session)
    }
    val scope = rememberCoroutineScope()
    var reload by remember(playlist.id) { mutableIntStateOf(0) }
    var tracks by remember(playlist.id) { mutableStateOf<List<QqMusicAccountClient.Track>>(emptyList()) }
    var isLoading by remember(playlist.id) { mutableStateOf(true) }
    var loadingMore by remember(playlist.id) { mutableStateOf(false) }
    var canLoadMore by remember(playlist.id) { mutableStateOf(false) }
    var error by remember(playlist.id) { mutableStateOf<String?>(null) }
    var playError by remember(playlist.id) { mutableStateOf<String?>(null) }
    var busyIndex by remember(playlist.id) { mutableStateOf<Int?>(null) }

    LaunchedEffect(playlist.id, reload) {
        isLoading = true
        error = null
        tracks = emptyList()
        canLoadMore = false
        try {
            val page = client.playlistTracks(playlist, offset = 0, count = 100)
            tracks = page
            canLoadMore = page.size == 100
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.localizedMessage ?: "QQ 音乐歌单读取失败"
        } finally {
            isLoading = false
        }
    }

    fun play(index: Int) {
        if (busyIndex != null || index !in tracks.indices) return
        val song = tracks[index]
        scope.launch {
            busyIndex = index
            playError = null
            try {
                val url = client.streamUrl(song.mid, song.mediaMid)
                // Lyrics must never block an already authorized audio stream.
                var lyric: String? = try {
                    withTimeoutOrNull(5_000L) { lyricClient.lyrics(song.mid).lrc }
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { null }
                if (lyric == null) {
                    lyric = try {
                        withTimeoutOrNull(4_000L) {
                            pluginClient.lyrics("qqmusic.direct", song.mid, "https://u.y.qq.com")
                        }?.takeIf(QqMusicLyricCodec::hasTimedLines)
                    } catch (e: CancellationException) { throw e }
                      catch (_: Exception) { null }
                }
                // Publish the ordered, currently loaded QQ tracks to the host before playback.
                onQueueReady?.invoke(tracks.toList(), index)
                onPlayTrack(
                    OnlinePlayableTrack(
                        mediaId = "qqmusic:song:${song.mid}",
                        title = song.name,
                        artist = song.artist,
                        artworkUrl = song.artworkUrl,
                        streamUrl = url,
                        lyrics = lyric
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                playError = "播放失败：${e.localizedMessage.orEmpty()}"
            } finally {
                busyIndex = null
            }
        }
    }

    SharedPlaylistDetailContent(
        title = playlist.name,
        coverUrl = playlist.coverUrl,
        ownerLabel = "QQ 音乐歌单",
        totalCount = maxOf(playlist.songCount, tracks.size),
        songs = tracks.mapIndexed { i, song ->
            DetailSongUi(
                key = "${song.mid}_$i",
                title = song.name,
                artist = song.artist,
                album = song.album,
                artworkUrl = song.artworkUrl
            )
        },
        isLoading = isLoading,
        isLoadingMore = loadingMore,
        canLoadMore = canLoadMore,
        error = error,
        playError = playError,
        busyIndex = busyIndex,
        showMiniPlayer = showMiniPlayer,
        onBack = onBack,
        onRefresh = { reload++ },
        playActionTitle = "从首曲播放",
        onPlay = { index -> play(index) },
        onLoadMore = {
            if (!loadingMore && canLoadMore) scope.launch {
                loadingMore = true
                try {
                    val page = client.playlistTracks(playlist, offset = tracks.size, count = 100)
                    val merged = (tracks + page).distinctBy { it.mid }
                    canLoadMore = page.size == 100 && merged.size > tracks.size
                    tracks = merged
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = "后续歌曲加载失败：${e.localizedMessage.orEmpty()}"
                } finally {
                    loadingMore = false
                }
            }
        }
    )
}
