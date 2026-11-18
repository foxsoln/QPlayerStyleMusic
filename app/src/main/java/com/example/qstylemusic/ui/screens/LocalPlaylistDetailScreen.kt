package com.example.qstylemusic.ui.screens

import androidx.compose.runtime.Composable
import com.example.qstylemusic.data.Song
import com.example.qstylemusic.playback.OnlineFavorite

/** Local favourites / recent plays use the same visual shell as cloud playlists. */
@Composable
fun LocalPlaylistDetailScreen(
    kind: String,
    allSongs: List<Song>,
    favorites: Set<Long>,
    recentIds: List<Long>,
    onBack: () -> Unit,
    onPlaySong: (Song, List<Song>) -> Unit,
    showMiniPlayer: Boolean = false,
    onlineFavorites: List<OnlineFavorite> = emptyList(),
    onPlayOnlineFavorite: (OnlineFavorite) -> Unit = {}
) {
    val ordered = when (kind) {
        "recent" -> recentIds.mapNotNull { id -> allSongs.find { it.id == id } }
        else -> allSongs.filter { it.id in favorites }
    }
    val remote = if (kind == "favorites") onlineFavorites else emptyList()
    val title = if (kind == "recent") "最近播放" else "我喜欢"
    SharedPlaylistDetailContent(
        title = title,
        coverUrl = ordered.firstOrNull()?.artworkUri ?: remote.firstOrNull()?.artworkUrl,
        ownerLabel = "本地歌单",
        totalCount = ordered.size + remote.size,
        songs = ordered.mapIndexed { i, song ->
            DetailSongUi(
                key = "local_${song.id}_$i",
                title = song.title,
                artist = song.artist,
                album = song.album,
                artworkUrl = song.artworkUri
            )
        } + remote.mapIndexed { i, song ->
            DetailSongUi(
                key = "remote_${song.mediaId}_$i",
                title = song.title,
                artist = song.artist,
                album = when {
                    song.mediaId.startsWith("qqmusic:") -> "QQ 音乐 · 本机收藏"
                    song.mediaId.startsWith("netease:") -> "网易云音乐 · 本机收藏"
                    else -> "在线音源 · 本机收藏"
                },
                artworkUrl = song.artworkUrl
            )
        },
        isLoading = false,
        isLoadingMore = false,
        canLoadMore = false,
        error = null,
        playError = null,
        busyIndex = null,
        showMiniPlayer = showMiniPlayer,
        onBack = onBack,
        onRefresh = null,
        onPlay = { index ->
            if (index < ordered.size) ordered.getOrNull(index)?.let { onPlaySong(it, ordered) }
            else remote.getOrNull(index - ordered.size)?.let(onPlayOnlineFavorite)
        },
        onLoadMore = {}
    )
}
