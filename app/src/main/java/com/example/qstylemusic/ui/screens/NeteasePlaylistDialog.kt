package com.example.qstylemusic.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.qstylemusic.plugin.auth.NeteaseAccountClient
import com.example.qstylemusic.plugin.auth.NeteaseAccountPlaylist
import com.example.qstylemusic.plugin.auth.NeteaseAccountPlaylistDetail
import com.example.qstylemusic.plugin.auth.NeteaseAccountSong
import kotlinx.coroutines.CancellationException

/** Home recommended playlist details; same playlist playback pipeline as Mine. */
@Composable
fun NeteasePlaylistDialog(
    playlist: NeteaseAccountPlaylist,
    onDismiss: () -> Unit,
    onPlay: (List<NeteaseAccountSong>, Int) -> Unit
) {
    val context = LocalContext.current
    val client = remember(context) { NeteaseAccountClient(context.applicationContext) }
    var detail by remember(playlist.id) { mutableStateOf<NeteaseAccountPlaylistDetail?>(null) }
    var error by remember(playlist.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(playlist.id) {
        try { detail = client.playlistDetail(playlist.id) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.localizedMessage ?: "歌单读取失败" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(playlist.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            when {
                error != null -> Text("歌单加载失败：$error", color = MaterialTheme.colorScheme.error)
                detail == null -> CircularProgressIndicator()
                else -> {
                    val songs = detail!!.songs
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                            Row(Modifier.fillMaxWidth().clickable { onPlay(songs, index) }
                                .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AsyncImage(model = song.artworkUrl, contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(46.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(song.artist, style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            Button(onClick = {
                detail?.songs?.takeIf { it.isNotEmpty() }?.let { onPlay(it, 0) }
            }, enabled = detail?.songs?.isNotEmpty() == true) {
                Text("播放全部")
            }
        }
    )
}
