package com.example.qstylemusic.viewmodel

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.qstylemusic.data.MusicScanner
import com.example.qstylemusic.data.Song
import com.example.qstylemusic.plugin.OnlinePlayableTrack
import com.example.qstylemusic.plugin.auth.NeteaseAccountClient
import com.example.qstylemusic.plugin.auth.NeteaseAccountSong
import com.example.qstylemusic.playback.MusicPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** All screens observe this ViewModel; none owns an ExoPlayer instance. */
data class PlaybackUiState(
    val currentSongId: Long? = null,
    val currentMediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val artworkUri: Uri? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val connected: Boolean = false
) {
    val hasSong: Boolean get() = currentMediaId != null || currentSongId != null
}

/** A row in the actual Media3 playback playlist. */
data class PlayQueueItemUi(
    val mediaId: String,
    val title: String,
    val artist: String,
    val artworkUri: Uri? = null
)

data class PlayQueueUiState(
    val items: List<PlayQueueItemUi> = emptyList(),
    val currentIndex: Int = -1
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val scanner = MusicScanner(application)
    private val preferences = application.getSharedPreferences("music_ui_preferences", 0)

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs = _songs.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback = _playback.asStateFlow()

    // Updated only when the playlist or the selected media item changes.
    // Playback progress polling does NOT rebuild this potentially long list.
    private val _playQueue = MutableStateFlow(PlayQueueUiState())
    val playQueue = _playQueue.asStateFlow()

    // Online lyric belongs to the single online track currently loaded.
    // Local tracks still use LyricsRepository / embedded LRC.
    private val _onlineLyrics = MutableStateFlow<String?>(null)
    val onlineLyrics = _onlineLyrics.asStateFlow()

    private val _favoriteIds = MutableStateFlow(
        preferences.getStringSet("favorite_ids", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
    )
    val favoriteIds = _favoriteIds.asStateFlow()

    private val _recentIds = MutableStateFlow(
        preferences.getString("recent_ids", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
    )
    val recentIds = _recentIds.asStateFlow()

    private val _darkTheme = MutableStateFlow(preferences.getBoolean("dark_theme", true))
    val darkTheme = _darkTheme.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val token = SessionToken(application, ComponentName(application, MusicPlaybackService::class.java))
    private val future: ListenableFuture<MediaController> = MediaController.Builder(application, token).buildAsync()
    private var controller: MediaController? = null
    private var pendingPlay: Pair<Long, List<Song>>? = null
    private var pendingOnline: OnlinePlayableTrack? = null
    private var pendingNetease: Pair<List<NeteaseAccountSong>, Int>? = null
    private val neteaseClient = NeteaseAccountClient(application.applicationContext)
    private var lyricJob: Job? = null
    private var lyricNativeMediaId: String? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncState(player)
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)
            ) {
                syncPlayQueue(player)
                updateNeteaseLyrics(player)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val isNetease = controller?.currentMediaItem?.mediaId?.startsWith("netease:song:") == true
            _error.value = if (isNetease) {
                "网易云播放失败：${error.localizedMessage.orEmpty()}。请检查账号播放权限或网络。"
            } else {
                "播放失败：${error.localizedMessage.orEmpty()}"
            }
        }
    }

    init {
        future.addListener({
            runCatching {
                val attached = future.get()
                controller = attached
                attached.addListener(playerListener)
                syncState(attached)
                syncPlayQueue(attached)
                pendingPlay?.let { (id, queue) ->
                    pendingPlay = null
                    startQueue(id, queue)
                }
                pendingOnline?.let { track ->
                    pendingOnline = null
                    playOnlineTrack(track)
                }
                pendingNetease?.let { (tracks, index) ->
                    pendingNetease = null
                    playNeteasePlaylist(tracks, index)
                }
            }.onFailure { throwable ->
                _error.value = "连接播放服务失败：${throwable.localizedMessage.orEmpty()}"
            }
        }, ContextCompat.getMainExecutor(application))

        viewModelScope.launch {
            while (isActive) {
                controller?.let { syncState(it) }
                delay(400L)
            }
        }
    }

    fun refreshSongs() {
        viewModelScope.launch {
            runCatching { scanner.querySongs() }
                .onSuccess {
                    _songs.value = it
                    _error.value = null
                }
                .onFailure { _error.value = "音乐扫描失败：${it.localizedMessage.orEmpty()}" }
        }
    }

    /** Enqueue all selected playlist songs. Each URL is resolved on demand by
     *  MusicPlaybackService, so next/previous/repeat work on the real Media3 queue.
     */
    fun playNeteasePlaylist(songs: List<NeteaseAccountSong>, startIndex: Int) {
        if (songs.isEmpty() || startIndex !in songs.indices) return
        val player = controller
        if (player == null) {
            pendingNetease = songs to startIndex
            pendingOnline = null
            pendingPlay = null
            return
        }
        pendingNetease = null
        pendingOnline = null
        pendingPlay = null
        lyricJob?.cancel()
        lyricNativeMediaId = null
        _onlineLyrics.value = null
        _error.value = null
        val items = songs.map { song ->
            MediaItem.Builder()
                .setMediaId("netease:song:${song.id}")
                .setUri(Uri.parse("qstyle://netease/song/${song.id}"))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(song.artworkUrl?.let(Uri::parse))
                        .build()
                )
                .build()
        }
        player.setMediaItems(items, startIndex, 0L)
        player.prepare()
        player.play()
        syncState(player)
        syncPlayQueue(player)
        updateNeteaseLyrics(player)
    }

    /** Fetch lyrics for currently playing native NetEase queue track only. */
    private fun updateNeteaseLyrics(player: Player) {
        val item = player.currentMediaItem
        val mediaId = item?.mediaId.orEmpty()
        val uri = item?.localConfiguration?.uri
        val isNative = mediaId.startsWith("netease:song:") &&
            uri?.scheme == "qstyle" && uri.host == "netease"
        if (!isNative) {
            lyricNativeMediaId = null
            return
        }
        if (lyricNativeMediaId == mediaId) return
        lyricNativeMediaId = mediaId
        lyricJob?.cancel()
        _onlineLyrics.value = null
        val songId = mediaId.removePrefix("netease:song:")
        lyricJob = viewModelScope.launch {
            try {
                val lyric = neteaseClient.lyrics(songId)
                if (_playback.value.currentMediaId == mediaId) {
                    _onlineLyrics.value = lyric
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Missing online lyrics should never block a legitimately playable song.
            }
        }
    }

    /** Play a stream that the current user's source API has authorized. */
    fun playOnlineTrack(track: OnlinePlayableTrack) {
        val player = controller
        if (player == null) {
            pendingOnline = track
            pendingPlay = null
            pendingNetease = null
            return
        }
        lyricJob?.cancel()
        lyricNativeMediaId = null
        _error.value = null
        val mediaItem = MediaItem.Builder()
            .setMediaId(track.mediaId)
            .setUri(track.streamUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setArtworkUri(track.artworkUrl?.takeIf { it.startsWith("https://") }?.let(Uri::parse))
                    .build()
            )
            .build()
        _onlineLyrics.value = track.lyrics
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        syncState(player)
        syncPlayQueue(player)
    }

    fun playSong(song: Song, fromQueue: List<Song> = _songs.value) {
        val queue = fromQueue.ifEmpty { listOf(song) }
        if (controller == null) {
            pendingPlay = song.id to queue
            pendingOnline = null
            pendingNetease = null
            return
        }
        startQueue(song.id, queue)
    }

    private fun startQueue(id: Long, queue: List<Song>) {
        val player = controller ?: return
        lyricJob?.cancel()
        lyricNativeMediaId = null
        _onlineLyrics.value = null
        _error.value = null
        val index = queue.indexOfFirst { it.id == id }.coerceAtLeast(0)
        val mediaItems = queue.map { item ->
            MediaItem.Builder()
                .setMediaId(item.id.toString())
                .setUri(item.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.artist)
                        .setAlbumTitle(item.album)
                        .setArtworkUri(item.artworkUri)
                        .build()
                )
                .build()
        }
        player.setMediaItems(mediaItems, index, 0L)
        player.prepare()
        player.play()
        _recentIds.value = (listOf(id) + _recentIds.value.filterNot { it == id }).take(30)
        preferences.edit().putString("recent_ids", _recentIds.value.joinToString(",")).apply()
        syncState(player)
        syncPlayQueue(player)
    }

    /** Plays a specific item in the currently loaded Media3 queue. */
    fun playQueueItem(index: Int) {
        val player = controller ?: return
        if (index !in 0 until player.mediaItemCount) return
        if (!player.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)) return
        player.seekToDefaultPosition(index)
        player.play()
        syncState(player)
        syncPlayQueue(player)
        updateNeteaseLyrics(player)
    }

    /** Mirror the actual Media3 playlist to the sheet UI. */
    private fun syncPlayQueue(player: Player) {
        if (!player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) {
            _playQueue.value = PlayQueueUiState()
            return
        }
        val songsById = _songs.value.associateBy { it.id.toString() }
        val items = (0 until player.mediaItemCount).map { index ->
            val item = player.getMediaItemAt(index)
            val meta = item.mediaMetadata
            val song = songsById[item.mediaId]
            PlayQueueItemUi(
                mediaId = item.mediaId,
                title = meta.title?.toString().orEmpty().ifBlank {
                    song?.title ?: "未知歌曲"
                },
                artist = meta.artist?.toString().orEmpty().ifBlank {
                    song?.artist ?: "未知歌手"
                },
                artworkUri = meta.artworkUri ?: song?.artworkUri
            )
        }
        _playQueue.value = PlayQueueUiState(
            items = items,
            currentIndex = player.currentMediaItemIndex.takeIf { it in items.indices } ?: -1
        )
    }

    fun togglePlay() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() {
        controller?.let { if (it.currentPosition > 3_000L) it.seekTo(0L) else it.seekToPreviousMediaItem() }
    }
    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs.coerceAtLeast(0L)) }
    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled; syncState(it) }
    }
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            syncState(it)
        }
    }
    /** Three exclusive queue modes: sequential -> repeat-all -> shuffle -> sequential. */
    fun cyclePlayMode() {
        val player = controller ?: return
        when {
            // Shuffle behaves as an endless shuffled playlist.
            player.shuffleModeEnabled -> {
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_OFF
            }
            // Includes the old repeat-one state for compatibility with V0.3.
            player.repeatMode != Player.REPEAT_MODE_OFF -> {
                player.shuffleModeEnabled = true
                player.repeatMode = Player.REPEAT_MODE_ALL
            }
            else -> {
                player.shuffleModeEnabled = false
                player.repeatMode = Player.REPEAT_MODE_ALL
            }
        }
        syncState(player)
    }

    fun toggleFavorite(id: Long) {
        val updated = _favoriteIds.value.toMutableSet()
        if (!updated.add(id)) updated.remove(id)
        _favoriteIds.value = updated
        preferences.edit().putStringSet("favorite_ids", updated.map(Long::toString).toSet()).apply()
    }
    fun setDarkTheme(isDark: Boolean) {
        _darkTheme.value = isDark
        preferences.edit().putBoolean("dark_theme", isDark).apply()
    }

    private fun syncState(player: Player) {
        val mediaItem = player.currentMediaItem
        val meta = mediaItem?.mediaMetadata
        val id = mediaItem?.mediaId?.toLongOrNull()
        val song = _songs.value.firstOrNull { it.id == id }
        val duration = player.duration.takeIf { it > 0 } ?: song?.durationMs ?: 0L
        _playback.value = PlaybackUiState(
            currentSongId = id,
            currentMediaId = mediaItem?.mediaId,
            title = meta?.title?.toString().orEmpty().ifBlank { song?.title.orEmpty() },
            artist = meta?.artist?.toString().orEmpty().ifBlank { song?.artist.orEmpty() },
            artworkUri = meta?.artworkUri ?: song?.artworkUri,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            repeatMode = player.repeatMode,
            shuffle = player.shuffleModeEnabled,
            connected = true
        )
    }

    override fun onCleared() {
        lyricJob?.cancel()
        controller?.removeListener(playerListener)
        MediaController.releaseFuture(future)
        controller = null
        super.onCleared()
    }
}
