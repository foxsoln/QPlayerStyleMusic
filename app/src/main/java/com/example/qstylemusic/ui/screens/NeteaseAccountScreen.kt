package com.example.qstylemusic.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
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
import com.example.qstylemusic.plugin.auth.NeteaseAccountClient
import com.example.qstylemusic.plugin.auth.NeteaseAccountLibrary
import com.example.qstylemusic.plugin.auth.NeteaseAccountPlaylist
import com.example.qstylemusic.plugin.auth.NeteaseAccountPlaylistDetail
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * V1.0: read-only account page for an already authenticated NetEase session.
 * Does NOT change QR login or automatically start online playback.
 */
@Composable
fun NeteaseAccountScreen(
    onBack: () -> Unit,
    embedded: Boolean = false,
    onPlayPlaylist: ((NeteaseAccountPlaylistDetail, Int) -> Unit)? = null,
    onOpenLogin: (() -> Unit)? = null,
    onOpenPlaylist: ((NeteaseAccountPlaylist) -> Unit)? = null,
    refreshKey: Int = 0
) {
    val context = LocalContext.current
    val client = remember(context) { NeteaseAccountClient(context.applicationContext) }
    var reload by remember { mutableIntStateOf(0) }
    var library by remember { mutableStateOf<NeteaseAccountLibrary?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<NeteaseAccountPlaylist?>(null) }
    var details by remember { mutableStateOf<NeteaseAccountPlaylistDetail?>(null) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("全部") }
    val scope = rememberCoroutineScope()
    var resolvingId by remember { mutableStateOf<String?>(null) }
    var playError by remember { mutableStateOf<String?>(null) }

    fun requestPlay(detail: NeteaseAccountPlaylistDetail, index: Int) {
        if (onPlayPlaylist == null || index !in detail.songs.indices || resolvingId != null) return
        val song = detail.songs[index]
        scope.launch {
            resolvingId = song.id
            playError = null
            try {
                // Preflight the selected song only. Do not resolve every track in advance.
                client.streamUrl(song.id)
                onPlayPlaylist(detail, index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                playError = "无法播放「${song.title}」：${e.localizedMessage.orEmpty()}"
            } finally {
                resolvingId = null
            }
        }
    }

    BackHandler(enabled = selected != null || !embedded) {
        if (selected != null) selected = null else onBack()
    }

    LaunchedEffect(reload, refreshKey) {
        loading = true
        loadError = null
        try {
            if (!client.hasLocalSession) {
                loadError = "本机尚未保存网易云登录态。请返回原网易云音源，使用 PC 模式扫码登录。"
            } else {
                library = client.myLibrary()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = "账号数据读取失败：${e.localizedMessage.orEmpty()}"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(selected?.id, reload, refreshKey) {
        val playlist = selected ?: run {
            details = null
            return@LaunchedEffect
        }
        detailLoading = true
        details = null
        detailError = null
        playError = null
        try {
            details = client.playlistDetail(playlist.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            detailError = "歌曲列表读取失败：${e.localizedMessage.orEmpty()}"
        } finally {
            detailLoading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp)) {
        if (!embedded || selected != null) Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (selected != null) selected = null else onBack()
            }) { Icon(Icons.Rounded.ArrowBack, contentDescription = "返回") }
            Text(
                if (selected == null) "我的网易云" else "歌单歌曲",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { reload++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新账号数据")
            }
        }

        val detailSelection = selected
        if (detailSelection != null) {
            Text(detailSelection.name, style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Text(
                "共 ${details?.trackCount ?: detailSelection.trackCount} 首 · 当前已读取 ${details?.songs?.size ?: 0} 首",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            when {
                detailLoading -> LoadingStatus()
                detailError != null -> FailureStatus(detailError.orEmpty(), { reload++ })
                else -> {
                    val data = details
                    if (data != null) {
                        if (onPlayPlaylist != null) {
                            Button(
                                enabled = data.songs.isNotEmpty() && resolvingId == null,
                                onClick = { requestPlay(data, 0) }
                            ) { Text(if (resolvingId != null) "正在验证播放权限…" else "播放全部") }
                            Text(
                                "点击歌曲即可从该曲开始播放整个歌单；能否播放取决于网易云账号权限。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        playError?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            items(data.songs, key = { it.id }) { song ->
                                val index = data.songs.indexOfFirst { it.id == song.id }
                                Card(Modifier.fillMaxWidth().clickable(
                                    enabled = onPlayPlaylist != null && resolvingId == null,
                                    onClick = { requestPlay(data, index) }
                                )) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        SongArtwork(song.artworkUrl)
                                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodyLarge)
                                            Text("${song.artist} · ${song.album}", maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return@Column
        }

        when {
            loading -> LoadingStatus()
            loadError != null -> {
                FailureStatus(loadError.orEmpty(), { reload++ })
                if (onOpenLogin != null) {
                    Button(onClick = onOpenLogin) { Text("登录音源账号") }
                }
            }
            else -> {
                val account = library ?: return@Column
                // In the My tab, the account identity lives in the top-right menu.
                // The standalone plugin account page retains its profile header.
                if (!embedded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = account.profile.avatarUrl,
                            contentDescription = "网易云账号头像",
                            modifier = Modifier.size(58.dp).clip(CircleShape)
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(account.profile.nickname,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium)
                            Text("网易云 UID：${account.profile.userId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                Text("我的歌单 · ${account.playlists.size}", style = MaterialTheme.typography.titleMedium)
                if (account.hasMore) {
                    Text("歌单数量较多，本次只加载前 400 条。", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("全部", "创建", "收藏").forEach { value ->
                        FilterChip(selected = filter == value, onClick = { filter = value },
                            label = { Text(value) })
                    }
                }
                val visible = account.playlists.filter { playlist ->
                    when (filter) {
                        "创建" -> playlist.isOwned
                        "收藏" -> !playlist.isOwned
                        else -> true
                    }
                }
                // Square cover grid, two columns as in the supplied screenshot.
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    gridItems(visible, key = { it.id }) { playlist ->
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (embedded && onOpenPlaylist != null) {
                                    onOpenPlaylist(playlist)
                                } else {
                                    selected = playlist
                                }
                            },
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            AsyncImage(
                                model = playlist.artworkUrl,
                                contentDescription = playlist.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                    .clip(RoundedCornerShape(14.dp))
                            )
                            Text(
                                if (playlist.isFavorite) "♥ ${playlist.name}" else playlist.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${playlist.trackCount} 首 · ${if (playlist.isOwned) "创建" else "收藏"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongArtwork(url: String?) {
    if (url == null) {
        Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null)
        }
    } else {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.size(50.dp).clip(MaterialTheme.shapes.small)
        )
    }
}

@Composable
private fun LoadingStatus() {
    Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun FailureStatus(message: String, retry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = retry) { Text("重试") }
    }
}
