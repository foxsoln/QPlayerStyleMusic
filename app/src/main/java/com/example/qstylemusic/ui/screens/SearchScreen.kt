package com.example.qstylemusic.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.qstylemusic.data.Song
import com.example.qstylemusic.plugin.auth.NeteaseAccountClient
import com.example.qstylemusic.plugin.auth.NeteaseAccountSong
import com.example.qstylemusic.ui.components.SongRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Search local music and NetEase online music in one page. */
@Composable
fun SearchScreen(
    songs: List<Song>,
    favorites: Set<Long>,
    onPlay: (Song) -> Unit,
    onFavorite: (Long) -> Unit,
    onPlayNetease: (List<NeteaseAccountSong>, Int) -> Unit,
    onOpenNeteaseLogin: () -> Unit,
    accountRefreshKey: Int = 0
) {
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val client = remember(context) { NeteaseAccountClient(context.applicationContext) }
    var cloudResults by remember { mutableStateOf<List<NeteaseAccountSong>>(emptyList()) }
    var cloudError by remember { mutableStateOf<String?>(null) }
    var cloudLoading by remember { mutableStateOf(false) }
    val loggedIn = client.hasLocalSession
    val trimmed = query.trim()
    val filtered = if (trimmed.isBlank()) songs.take(25) else songs.filter {
        it.title.contains(trimmed, ignoreCase = true) ||
            it.artist.contains(trimmed, ignoreCase = true) ||
            it.album.contains(trimmed, ignoreCase = true)
    }

    LaunchedEffect(trimmed, accountRefreshKey) {
        cloudResults = emptyList()
        cloudError = null
        cloudLoading = false
        if (trimmed.isEmpty() || !client.hasLocalSession) return@LaunchedEffect
        delay(400L) // Debounce typing; cancel the previous query on edit.
        cloudLoading = true
        try {
            cloudResults = client.searchSongs(trimmed, 30)
        } catch (e: CancellationException) { throw e }
          catch (e: Exception) {
            cloudError = "网易云搜索失败：${e.localizedMessage.orEmpty()}"
        } finally {
            cloudLoading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
            placeholder = { Text("搜索本地与网易云歌曲、歌手") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.large
        )
        LazyColumn(Modifier.fillMaxSize()) {
            if (trimmed.isNotBlank()) {
                item {
                    Text("网易云音乐", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                if (!loggedIn) item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text("请先登录网易云账号，之后可以在此搜索在线歌曲。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onOpenNeteaseLogin) { Text("登录网易云") }
                    }
                }
                if (cloudLoading) item {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp))
                        Text("正在搜索网易云…")
                    }
                }
                if (cloudError != null) item {
                    Text(cloudError.orEmpty(), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp))
                }
                if (loggedIn && !cloudLoading && cloudError == null && cloudResults.isEmpty()) item {
                    Text("暂无网易云搜索结果", modifier = Modifier.padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                itemsIndexed(cloudResults, key = { _, song -> "net_${song.id}" }) { index, song ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onPlayNetease(cloudResults, index) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AsyncImage(
                            model = song.artworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(52.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium)
                            Text(song.artist + " · 网易云音乐", maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Text(if (trimmed.isBlank()) "本地 · 最近添加" else "本地音乐 · ${filtered.size} 首",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
            }
            items(filtered, key = { "local_${it.id}" }) { song ->
                SongRow(song, song.id in favorites,
                    { onPlay(song) }, { onFavorite(song.id) })
            }
        }
    }
}
