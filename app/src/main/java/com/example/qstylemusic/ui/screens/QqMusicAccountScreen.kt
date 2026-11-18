package com.example.qstylemusic.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
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
 * Account playlists for QQ Music, visually integrated into My -> QQ 音乐.
 * Keeps the existing NetEase and local library unchanged.
 */
@Composable
fun QqMusicAccountScreen(
    onOpenLogin: () -> Unit,
    onPlayTrack: (OnlinePlayableTrack) -> Unit,
    onOpenPlaylist: ((QqMusicAccountClient.Playlist) -> Unit)? = null,
    refreshKey: Int = 0
) {
    val context = LocalContext.current
    val client = remember(context) { QqMusicAccountClient(context.applicationContext) }
    val lyricClient = remember(context) { QqMusicLyricClient(context.applicationContext) }
    val pluginClient = remember(context) {
        MplugOnlineClient(MplugStore(context.applicationContext), qqSession = client.session)
    }
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var accountPlaylists by remember { mutableStateOf<List<QqMusicAccountClient.Playlist>>(emptyList()) }
    var activePlaylist by remember { mutableStateOf<QqMusicAccountClient.Playlist?>(null) }
    var tracks by remember { mutableStateOf<List<QqMusicAccountClient.Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingTracks by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var canLoadMore by remember { mutableStateOf(false) }
    var busyMid by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var playError by remember { mutableStateOf<String?>(null) }
    var lyricNotice by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = activePlaylist != null) { activePlaylist = null }

    LaunchedEffect(refreshKey, reload) {
        loading = true
        accountPlaylists = emptyList()
        error = null
        if (!client.hasLocalSession) {
            error = "尚未登录 QQ 音乐。点击登录后在手机内生成二维码，使用 QQ 扫一扫进行授权。"
        } else {
            try { accountPlaylists = client.myPlaylists() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "QQ 歌单读取失败：${e.localizedMessage.orEmpty()}" }
        }
        loading = false
    }

    LaunchedEffect(activePlaylist?.id, reload, refreshKey) {
        val playlist = activePlaylist ?: return@LaunchedEffect
        tracks = emptyList()
        playError = null
        lyricNotice = null
        error = null
        loadingTracks = true
        try {
            val next = client.playlistTracks(playlist)
            tracks = next
            canLoadMore = next.size == 100
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "QQ 歌单详情读取失败：${e.localizedMessage.orEmpty()}" }
        finally { loadingTracks = false }
    }

    fun playSong(song: QqMusicAccountClient.Track) {
        if (busyMid != null) return
        scope.launch {
            busyMid = song.mid
            playError = null
            lyricNotice = null
            try {
                // Preserve the V2.2.4 authorized stream resolver unchanged.
                val url = client.streamUrl(song.mid, song.mediaMid)

                // QQ lyrics are keyed by song MID. Lyrics are independent of the
                // media_mid used to construct the audio file name.
                val native = lyricClient.lyrics(song.mid)
                var lrc = native.lrc
                if (lrc == null) {
                    // Compatible with users who still have the older .mplug installed.
                    // Plugin failures should not prevent a valid stream from playing.
                    try {
                        val candidate = withTimeoutOrNull(4_000L) {
                            pluginClient.lyrics("qqmusic.direct", song.mid, "https://u.y.qq.com")
                        }
                        lrc = candidate?.takeIf(QqMusicLyricCodec::hasTimedLines)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The native route's sanitized status remains visible below.
                    }
                }
                if (lrc == null) {
                    lyricNotice = "这首歌未取得带时间戳的歌词（${native.diagnostic.ifBlank { "接口未返回 LRC" }}）。音频仍可正常播放。"
                }
                onPlayTrack(OnlinePlayableTrack(
                    mediaId = "qqmusic:song:${song.mid}",
                    title = song.name,
                    artist = song.artist,
                    artworkUrl = song.artworkUrl,
                    streamUrl = url,
                    lyrics = lrc
                ))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { playError = "播放失败：${e.localizedMessage.orEmpty()}" }
            finally { busyMid = null }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (activePlaylist != null) {
            val chosen = requireNotNull(activePlaylist)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { activePlaylist = null }) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回 QQ 音乐歌单")
                }
                Column(Modifier.weight(1f)) {
                    Text(chosen.name, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text("已加载 ${tracks.size} 首", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (tracks.isNotEmpty()) {
                Button(onClick = { playSong(tracks.first()) }, enabled = busyMid == null) {
                    Text("播放首曲")
                }
            }
            Text("目前 QQ 账号歌曲采用单曲进入 Media3；后续可扩展完整 QQ 队列。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loadingTracks) {
                Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            playError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            lyricNotice?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                itemsIndexed(tracks, key = { _, track -> track.mid }) { _, track ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = busyMid == null) { playSong(track) }
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(model = track.artworkUrl, contentDescription = null,
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(9.dp)),
                            contentScale = ContentScale.Crop)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge)
                            Text("${track.artist} · ${track.album}", maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (busyMid == track.mid) CircularProgressIndicator(Modifier.size(22.dp))
                    }
                }
                if (canLoadMore) item {
                    OutlinedButton(enabled = !loadingMore, onClick = {
                        scope.launch {
                            loadingMore = true
                            try {
                                val more = client.playlistTracks(chosen, tracks.size)
                                tracks = (tracks + more).distinctBy { it.mid }
                                canLoadMore = more.size == 100
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = "加载更多失败：${e.localizedMessage.orEmpty()}" }
                            finally { loadingMore = false }
                        }
                    }) { Text(if (loadingMore) "加载中…" else "继续加载歌曲") }
                }
            }
            return@Column
        }

        val profile = client.profile()
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text("QQ 音乐歌单", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { reload++ }) { Text("刷新") }
        }
        if (profile == null) {
            Text("QQ 音乐使用独立插件登录凭证，不与网易云共享账号信息。",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Button(onClick = onOpenLogin) { Text("QQ 扫码登录") }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 12.dp)) {
            AsyncImage(model = profile.avatarUrl, contentDescription = "QQ 头像",
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)))
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(profile.displayName, style = MaterialTheme.typography.bodyMedium)
                Text("本机已保存会话，账号接口的实际授权情况以读取结果为准",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (loading) CircularProgressIndicator()
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onOpenLogin) { Text("重新登录 QQ 音乐") }
        }
        if (!loading && error == null && accountPlaylists.isEmpty()) {
            Text("接口没有返回歌单。可能是账号中暂无歌单、会话权限不足，或 QQ 音乐接口已调整。",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(accountPlaylists, key = { it.id }) { pl ->
                Column(modifier = Modifier.fillMaxWidth().clickable {
                        if (onOpenPlaylist != null) onOpenPlaylist(pl) else activePlaylist = pl
                    }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        if (pl.coverUrl != null) {
                            AsyncImage(model = pl.coverUrl, contentDescription = null,
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else Icon(Icons.Rounded.MusicNote, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(pl.name, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 5.dp))
                    Text("${pl.songCount} 首", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
