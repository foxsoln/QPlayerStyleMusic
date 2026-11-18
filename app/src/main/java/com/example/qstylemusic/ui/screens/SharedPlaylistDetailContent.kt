package com.example.qstylemusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * Shared detail presentation for non-NetEase playlists.
 * Mirrors the established NeteasePlaylistDetailScreen structure:
 * full-bleed blurred-cover hero, rounded song sheet, play-all action, search and list.
 * This composable is UI-only; no login, stream resolution or lyric logic belongs here.
 */
internal data class DetailSongUi(
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: Any?
)

@Composable
internal fun SharedPlaylistDetailContent(
    title: String,
    coverUrl: Any?,
    ownerLabel: String,
    totalCount: Int,
    songs: List<DetailSongUi>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    canLoadMore: Boolean,
    error: String?,
    playError: String?,
    busyIndex: Int?,
    showMiniPlayer: Boolean,
    onBack: () -> Unit,
    onRefresh: (() -> Unit)?,
    playActionTitle: String = "播放全部",
    onPlay: (Int) -> Unit,
    onLoadMore: () -> Unit
) {
    var searchExpanded by remember(title) { mutableStateOf(false) }
    var keyword by remember(title) { mutableStateOf("") }
    val filtered = remember(songs, keyword) {
        songs.withIndex().filter { indexed ->
            keyword.isBlank() || indexed.value.title.contains(keyword, ignoreCase = true) ||
                indexed.value.artist.contains(keyword, ignoreCase = true) ||
                indexed.value.album.contains(keyword, ignoreCase = true)
        }
    }
    val surface = MaterialTheme.colorScheme.surface
    val ink = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scrollBottom = bottomInset + (if (showMiniPlayer) 118.dp else 24.dp)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(surface),
        contentPadding = PaddingValues(bottom = scrollBottom)
    ) {
        item(key = "hero") {
            Box(Modifier.fillMaxWidth().height(270.dp + topInset)) {
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur(36.dp)
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF27383D).copy(alpha = .82f),
                                Color(0xFF354A4D).copy(alpha = .90f),
                                Color(0xFF31474B).copy(alpha = .96f)
                            )
                        )
                    )
                )
                Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = topInset).height(58.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Rounded.ArrowBack, "返回我的歌单", tint = Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { searchExpanded = !searchExpanded; keyword = "" }) {
                            Icon(Icons.Rounded.Search, "搜索歌单", tint = Color.White)
                        }
                        if (onRefresh != null) {
                            IconButton(onClick = onRefresh) {
                                Icon(Icons.Rounded.Refresh, "刷新歌单", tint = Color.White)
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(
                            Modifier.size(96.dp).clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = .12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (coverUrl != null) {
                                AsyncImage(
                                    model = coverUrl,
                                    contentDescription = title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(Icons.Rounded.MusicNote, null, tint = Color.White,
                                    modifier = Modifier.size(40.dp))
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(title, color = Color.White, fontWeight = FontWeight.Bold,
                                fontSize = 21.sp, maxLines = 3, lineHeight = 27.sp,
                                overflow = TextOverflow.Ellipsis)
                            Text(ownerLabel, color = Color.White.copy(alpha = .76f),
                                fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$totalCount 首歌曲",
                        color = Color.White.copy(alpha = .76f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 4.dp, bottom = 21.dp)
                    )
                }
            }
        }
        item(key = "actions") {
            Box(Modifier.fillMaxWidth().background(Color(0xFF31474B))) {
                Surface(
                    color = surface,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(72.dp)
                                .padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(43.dp).clip(CircleShape)
                                    .background(primary)
                                    .clickable(enabled = songs.isNotEmpty() && busyIndex == null) { onPlay(0) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (busyIndex == 0) CircularProgressIndicator(
                                    modifier = Modifier.size(21.dp), strokeWidth = 2.dp,
                                    color = Color.White
                                ) else Icon(Icons.Rounded.PlayArrow, playActionTitle, tint = Color.White)
                            }
                            Column(
                                modifier = Modifier.weight(1f)
                                    .clickable(enabled = songs.isNotEmpty() && busyIndex == null) { onPlay(0) }
                                    .padding(start = 12.dp)
                            ) {
                                Text(playActionTitle, color = ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                                Text("$totalCount 首 · 已加载 ${songs.size} 首", color = secondary, fontSize = 11.sp)
                            }
                            IconButton(onClick = { searchExpanded = !searchExpanded; keyword = "" }) {
                                Icon(Icons.Rounded.Search, "搜索歌曲", tint = secondary)
                            }
                            if (onRefresh != null) {
                                IconButton(onClick = onRefresh) {
                                    Icon(Icons.Rounded.Refresh, "刷新", tint = secondary)
                                }
                            }
                        }
                        if (searchExpanded) {
                            OutlinedTextField(
                                value = keyword,
                                onValueChange = { keyword = it },
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 4.dp),
                                placeholder = { Text("搜索歌名、歌手或专辑") },
                                trailingIcon = {
                                    IconButton(onClick = { keyword = "" }) {
                                        Icon(Icons.Rounded.Close, "清除搜索")
                                    }
                                },
                                singleLine = true
                            )
                        }
                        playError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                        }
                        HorizontalDivider(color = ink.copy(alpha = .07f))
                    }
                }
            }
        }
        if (isLoading) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else {
            if (error != null) {
                item(key = "load_error") {
                    Column(Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("歌单读取失败：$error", color = MaterialTheme.colorScheme.error)
                        if (onRefresh != null) {
                            TextButton(onClick = onRefresh) { Text("重试") }
                        }
                    }
                }
            }
            if (filtered.isEmpty() && error == null) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                        Text(if (keyword.isBlank()) "暂无歌曲" else "没有找到匹配的歌曲", color = secondary)
                    }
                }
            }
            items(filtered, key = { it.value.key }) { indexed ->
                Row(
                    modifier = Modifier.fillMaxWidth().background(surface)
                        .clickable(enabled = busyIndex == null) { onPlay(indexed.index) }
                        .padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier.size(47.dp).clip(RoundedCornerShape(7.dp))
                            .background(secondary.copy(alpha = .10f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (indexed.value.artworkUrl != null) {
                            AsyncImage(
                                model = indexed.value.artworkUrl,
                                contentDescription = indexed.value.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else Icon(Icons.Rounded.MusicNote, null, tint = secondary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(indexed.value.title, color = ink, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (indexed.value.album.isBlank()) indexed.value.artist
                                else "${indexed.value.artist} · ${indexed.value.album}",
                            color = secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp
                        )
                    }
                    if (busyIndex == indexed.index) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.PlayArrow, "播放此歌曲", tint = secondary)
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = 78.dp),
                    color = ink.copy(alpha = .045f)
                )
            }
            if (canLoadMore && keyword.isBlank()) {
                item(key = "load_more") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center) {
                        if (isLoadingMore) CircularProgressIndicator(Modifier.size(23.dp))
                        else TextButton(onClick = onLoadMore) { Text("继续加载歌曲") }
                    }
                }
            }
        }
    }
}
