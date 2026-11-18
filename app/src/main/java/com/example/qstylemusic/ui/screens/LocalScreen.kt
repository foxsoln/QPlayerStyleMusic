package com.example.qstylemusic.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.qstylemusic.data.Song
import com.example.qstylemusic.ui.components.SongRow

@Composable
fun LocalScreen(
    songs: List<Song>,
    favorites: Set<Long>,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    onPlay: (Song) -> Unit,
    onFavorite: (Long) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Text("共 ${songs.size} 首歌曲", Modifier.padding(start = 20.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!permissionGranted) {
            Text("需要授权访问媒体库", Modifier.padding(20.dp))
            Button(onClick = onRequestPermission, modifier = Modifier.padding(horizontal = 20.dp)) {
                Text("授予权限")
            }
        } else if (songs.isEmpty()) {
            Text("未找到本地音乐，请检查手机是否有歌曲文件。", Modifier.padding(20.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(songs, key = { it.id }) { song ->
                    SongRow(song, song.id in favorites, { onPlay(song) }, { onFavorite(song.id) })
                }
            }
        }
    }
}
