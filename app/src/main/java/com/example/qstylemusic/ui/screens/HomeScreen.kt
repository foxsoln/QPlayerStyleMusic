package com.example.qstylemusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.qstylemusic.data.Song
import com.example.qstylemusic.plugin.auth.NeteaseAccountClient
import com.example.qstylemusic.plugin.auth.NeteaseAccountPlaylist
import com.example.qstylemusic.plugin.auth.NeteaseAccountSong
import com.example.qstylemusic.ui.components.MusicArt
import com.example.qstylemusic.ui.components.SongRow
import kotlinx.coroutines.CancellationException

/** The home tab combines logged-in NetEase daily discoveries with local music. */
@Composable
fun HomeScreen(
    songs: List<Song>,
    recentIds: List<Long>,
    favorites: Set<Long>,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onOpenLocal: () -> Unit,
    onOpenFavorites: () -> Unit,
    onPlay: (Song) -> Unit,
    onFavorite: (Long) -> Unit,
    onPlayNetease: (List<NeteaseAccountSong>, Int) -> Unit,
    onOpenNeteaseLogin: () -> Unit,
    accountRefreshKey: Int = 0
) {
    val context = LocalContext.current
    val client = remember(context) { NeteaseAccountClient(context.applicationContext) }
    var dailySongs by remember { mutableStateOf<List<NeteaseAccountSong>>(emptyList()) }
    var recommended by remember { mutableStateOf<List<NeteaseAccountPlaylist>>(emptyList()) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var selectedPlaylist by remember { mutableStateOf<NeteaseAccountPlaylist?>(null) }
    val signedIn = client.hasLocalSession

    LaunchedEffect(accountRefreshKey, refresh) {
        dailySongs = emptyList()
        recommended = emptyList()
        remoteError = null
        if (!client.hasLocalSession) return@LaunchedEffect
        try {
            dailySongs = client.dailySongs()
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) { remoteError = "每日推荐读取失败：${e.localizedMessage.orEmpty()}" }
        try {
            recommended = client.recommendedPlaylists(8)
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            if (remoteError == null) remoteError = "推荐歌单读取失败：${e.localizedMessage.orEmpty()}"
        }
    }

    val recentSongs = recentIds.mapNotNull { id -> songs.find { it.id == id } }
    val suggested = (recentSongs + songs).distinctBy { it.id }.take(8)
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(17.dp)
    ) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 5.dp)) {
                Text("让每一次聆听，都更自在。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(17.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickEntry("本地音乐", "${songs.size} 首", Icons.Rounded.Folder,
                        Modifier.weight(1f), onOpenLocal)
                    QuickEntry("我喜欢的", "${favorites.size} 首", Icons.Rounded.Favorite,
                        Modifier.weight(1f), onOpenFavorites)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("网易云 · 每日推荐", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    if (signedIn) TextButton(onClick = { refresh++ }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Text("刷新")
                    }
                }
                if (!signedIn) {
                    Text("登录网易云账号后，在这里查看你的每日推荐歌曲。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onOpenNeteaseLogin) { Text("登录网易云") }
                } else if (dailySongs.isEmpty()) {
                    Text(remoteError ?: "暂无每日推荐，或正在加载…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("今天推荐 ${dailySongs.size} 首歌曲",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onPlayNetease(dailySongs, 0) }) {
                            Text("播放全部")
                        }
                    }
                }
            }
        }
        if (dailySongs.isNotEmpty()) item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                dailySongs.take(12).forEachIndexed { index, song ->
                    Column(Modifier.width(132.dp).clickable { onPlayNetease(dailySongs, index) },
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        AsyncImage(
                            model = song.artworkUrl,
                            contentDescription = song.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(132.dp).clip(RoundedCornerShape(17.dp))
                        )
                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (recommended.isNotEmpty()) {
            item {
                Text("网易云 · 推荐歌单", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }
            // Two-column cover cards, following the supplied reference screenshot.
            items(recommended.take(8).chunked(2)) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { playlist ->
                        Column(Modifier.weight(1f).clickable { selectedPlaylist = playlist },
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AsyncImage(
                                model = playlist.artworkUrl,
                                contentDescription = playlist.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                    .clip(RoundedCornerShape(16.dp))
                            )
                            Text(playlist.name, maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            Text("${playlist.trackCount} 首歌曲",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (!permissionGranted) item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("授予音乐读取权限后，即可看到手机中的歌曲。")
                Button(onClick = onRequestPermission) { Text("授权读取本地音乐") }
            }
        }
        if (suggested.isNotEmpty()) {
            item {
                Text("本地 · 为你准备", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    suggested.forEach { song ->
                        Column(Modifier.width(132.dp).clickable { onPlay(song) }) {
                            MusicArt(song.artworkUri, Modifier.size(132.dp), 20.dp)
                            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Text("本地 · 最近添加", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
            }
            items(songs.take(7), key = { "local_${it.id}" }) { song ->
                SongRow(song, song.id in favorites, { onPlay(song) }, { onFavorite(song.id) })
            }
        } else if (permissionGranted) {
            item {
                Text("本地音乐库为空，保存歌曲后可重新扫描。",
                    Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }

    selectedPlaylist?.let { playlist ->
        NeteasePlaylistDialog(
            playlist = playlist,
            onDismiss = { selectedPlaylist = null },
            onPlay = { list, index ->
                selectedPlaylist = null
                onPlayNetease(list, index)
            }
        )
    }
}

@Composable
private fun QuickEntry(
    label: String,
    details: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(details, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
