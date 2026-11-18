package com.example.qstylemusic.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.qstylemusic.plugin.auth.NeteaseAccountClient
import com.example.qstylemusic.plugin.auth.NeteaseAccountPlaylist
import com.example.qstylemusic.plugin.auth.NeteaseAccountPlaylistDetail
import com.example.qstylemusic.plugin.auth.NeteaseAccountSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * V1.8 - Independent playlist detail, inspired by the supplied reference layout.
 * It is opened from My playlists but lives outside the My page header/bottom tabs.
 * No changes to the successful PC QR authentication or the Media3 playback engine.
 */
@Composable
fun NeteasePlaylistDetailScreen(
    playlist: NeteaseAccountPlaylist,
    onBack: () -> Unit,
    onPlayPlaylist: (NeteaseAccountPlaylistDetail, Int) -> Unit,
    modifier: Modifier = Modifier,
    showMiniPlayer: Boolean = false
) {
    val context = LocalContext.current
    val client = remember(context) { NeteaseAccountClient(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var detail by remember(playlist.id) { mutableStateOf<NeteaseAccountPlaylistDetail?>(null) }
    var loading by remember(playlist.id) { mutableStateOf(true) }
    var loadError by remember(playlist.id) { mutableStateOf<String?>(null) }
    var refreshKey by remember(playlist.id) { mutableIntStateOf(0) }
    var loadedLimit by remember(playlist.id) { mutableIntStateOf(300) }
    var loadingMore by remember(playlist.id) { mutableStateOf(false) }
    var noMoreReturned by remember(playlist.id) { mutableStateOf(false) }
    var searching by remember(playlist.id) { mutableStateOf(false) }
    var searchQuery by remember(playlist.id) { mutableStateOf("") }
    var menuExpanded by remember(playlist.id) { mutableStateOf(false) }
    var showInfo by remember(playlist.id) { mutableStateOf(false) }
    var playIndex by remember(playlist.id) { mutableStateOf<Int?>(null) }
    var playError by remember(playlist.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(playlist.id, refreshKey, loadedLimit) {
        loading = detail == null
        loadingMore = detail != null
        loadError = null
        playError = null
        try {
            val oldSize = detail?.songs?.size ?: 0
            val updated = client.playlistDetail(playlist.id, maxTracks = loadedLimit)
            if (loadedLimit > 300 && updated.songs.size <= oldSize) noMoreReturned = true
            detail = updated
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.localizedMessage ?: "网易云歌单读取失败"
        } finally {
            loading = false
            loadingMore = false
        }
    }

    // Precheck the requested track only, then let the existing Media3 queue handle
    // the playlist. This preserves next/previous/shuffle/lyrics and rights checks.
    fun playFrom(index: Int) {
        val current = detail ?: return
        if (index !in current.songs.indices || playIndex != null) return
        scope.launch {
            playIndex = index
            playError = null
            try {
                client.streamUrl(current.songs[index].id)
                onPlayPlaylist(current, index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                playError = "无法播放「${current.songs[index].title}」：${e.localizedMessage.orEmpty()}"
            } finally {
                playIndex = null
            }
        }
    }

    val allTracks = detail?.songs.orEmpty()
    val visibleTracks = remember(detail, searchQuery) {
        allTracks.mapIndexed { index, song -> index to song }.filter { (_, song) ->
            searchQuery.isBlank() || song.title.contains(searchQuery, ignoreCase = true) ||
                song.artist.contains(searchQuery, ignoreCase = true) ||
                song.album.contains(searchQuery, ignoreCase = true)
        }
    }
    val pageSurface = MaterialTheme.colorScheme.surface
    val pageText = MaterialTheme.colorScheme.onSurface
    val pageMuted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val topSystemInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomSystemInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 给浮动迷你播放器留出滚动空间，而不是缩小整个详情页的高度。
    val scrollBottomPadding = bottomSystemInset + (if (showMiniPlayer) 118.dp else 24.dp)

    LazyColumn(
        modifier = modifier.fillMaxSize().background(pageSurface),
        contentPadding = PaddingValues(bottom = scrollBottomPadding)
    ) {
        item(key = "playlist_hero") {
            // 封面背景绘制在状态栏后面；顶部交互控件则避开系统状态栏。
            Box(Modifier.fillMaxWidth().height(270.dp + topSystemInset)) {
                AsyncImage(
                    model = playlist.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(36.dp)
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF27383D).copy(alpha = 0.82f),
                                Color(0xFF354A4D).copy(alpha = 0.90f),
                                Color(0xFF31474B).copy(alpha = 0.96f)
                            )
                        )
                    )
                )
                Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(top = topSystemInset)
                            .height(58.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "返回我的歌单", tint = Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { searching = !searching; if (!searching) searchQuery = "" }) {
                            Icon(Icons.Rounded.Search, contentDescription = "搜索当前歌单", tint = Color.White)
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "歌单更多操作", tint = Color.White)
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("刷新歌单") },
                                    leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                                    onClick = { menuExpanded = false; refreshKey++ }
                                )
                                DropdownMenuItem(
                                    text = { Text("分享歌单链接") },
                                    leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                                    onClick = { menuExpanded = false; copyMusicLink(context, "playlist", playlist.id) }
                                )
                                DropdownMenuItem(
                                    text = { Text("歌单信息") },
                                    leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                                    onClick = { menuExpanded = false; showInfo = true }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center) {
                            if (playlist.artworkUrl != null) {
                                AsyncImage(
                                    model = playlist.artworkUrl,
                                    contentDescription = "${playlist.name} 封面",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(
                                text = detail?.name ?: playlist.name,
                                color = Color.White, fontWeight = FontWeight.Bold,
                                fontSize = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                lineHeight = 27.sp
                            )
                            Text(
                                text = detail?.creatorName?.takeIf { it.isNotBlank() }
                                    ?: if (playlist.isOwned) "我创建的歌单" else "我收藏的歌单",
                                color = Color.White.copy(alpha = 0.76f),
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            if ((detail?.playCount ?: 0L) > 0L) {
                                Text(
                                    text = "${compactPlays(detail?.playCount ?: 0L)} 次播放",
                                    color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HeroAction(icon = Icons.Rounded.Share, title = "分享") {
                            sharePlaylist(context, playlist.id)
                        }
                        HeroAction(icon = Icons.Rounded.Info, title = "简介") {
                            showInfo = true
                        }
                        Text(
                            text = "${detail?.trackCount ?: playlist.trackCount} 首歌曲",
                            color = Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 5.dp)
                        )
                    }
                }
            }
        }

        item(key = "playlist_actions") {
            // Keep a dark backing at the rounded top corners of the music sheet.
            Box(Modifier.fillMaxWidth().background(Color(0xFF31474B))) {
            Surface(
                color = pageSurface,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(43.dp).clip(CircleShape)
                                .background(accent)
                                .clickable(enabled = allTracks.isNotEmpty() && playIndex == null) { playFrom(0) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (playIndex == 0) CircularProgressIndicator(
                                modifier = Modifier.size(21.dp), strokeWidth = 2.dp, color = Color.White
                            ) else Icon(Icons.Rounded.PlayArrow, contentDescription = "播放全部", tint = Color.White)
                        }
                        Column(
                            modifier = Modifier.weight(1f).clickable(
                                enabled = allTracks.isNotEmpty() && playIndex == null
                            ) { playFrom(0) }.padding(start = 12.dp)
                        ) {
                            Text("播放全部", color = pageText, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Text(
                                "${detail?.trackCount ?: playlist.trackCount} 首 · 已加载 ${allTracks.size} 首",
                                color = pageMuted, fontSize = 11.sp
                            )
                        }
                        IconButton(onClick = { searching = !searching; if (!searching) searchQuery = "" }) {
                            Icon(Icons.Rounded.Search, contentDescription = "在歌单中搜索", tint = pageMuted)
                        }
                        IconButton(onClick = { refreshKey++ }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新歌单", tint = pageMuted)
                        }
                    }
                    if (searching) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                            placeholder = { Text("搜索歌名、歌手或专辑") },
                            trailingIcon = {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "清除搜索")
                                }
                            },
                            singleLine = true
                        )
                    }
                    if (playError != null) {
                        Text(
                            text = playError.orEmpty(),
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    HorizontalDivider(color = pageText.copy(alpha = 0.07f))
                }
            }
            }
        }

        when {
            loading -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            loadError != null -> item(key = "loadError") {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("歌单加载失败：${loadError.orEmpty()}", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { refreshKey++ }) { Text("重试") }
                }
            }
            visibleTracks.isEmpty() -> item(key = "empty") {
                Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (searchQuery.isNotBlank()) "没有找到匹配的歌曲" else "歌单暂无可显示的歌曲",
                        color = pageMuted
                    )
                }
            }
            else -> items(visibleTracks, key = { (_, song) -> "song_${song.id}" }) { (index, song) ->
                PlaylistSongRow(
                    song = song,
                    busy = playIndex == index,
                    enabled = playIndex == null,
                    onPlay = { playFrom(index) },
                    onCopyLink = { copyMusicLink(context, "song", song.id) },
                    surfaceColor = pageSurface,
                    titleColor = pageText,
                    mutedColor = pageMuted
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 78.dp),
                    color = pageText.copy(alpha = 0.045f)
                )
            }
        }
        if (detail != null && allTracks.size < (detail?.trackCount ?: 0) &&
            loadedLimit < 3000 && !noMoreReturned && searchQuery.isBlank()) {
            item(key = "load_more") {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    if (loadingMore) {
                        CircularProgressIndicator(Modifier.size(25.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = { loadedLimit += 300 }) {
                            Text("继续加载歌曲 · 已读取 ${allTracks.size}/${detail?.trackCount ?: 0} 首")
                        }
                    }
                }
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("歌单信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("名称：${detail?.name ?: playlist.name}")
                    if (!detail?.creatorName.isNullOrBlank()) Text("创建者：${detail?.creatorName}")
                    Text("歌曲数量：${detail?.trackCount ?: playlist.trackCount} 首")
                    Text("歌单 ID：${playlist.id}")
                    detail?.description?.takeIf { it.isNotBlank() }?.let { Text("简介：$it") }
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("关闭") } }
        )
    }
}

@Composable
private fun HeroAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.13f))
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun PlaylistSongRow(
    song: NeteaseAccountSong,
    busy: Boolean,
    enabled: Boolean,
    onPlay: () -> Unit,
    onCopyLink: () -> Unit,
    surfaceColor: Color,
    titleColor: Color,
    mutedColor: Color
) {
    var menuExpanded by remember(song.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().background(surfaceColor)
            .clickable(enabled = enabled, onClick = onPlay)
            .padding(start = 18.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(47.dp).clip(RoundedCornerShape(7.dp))
                .background(mutedColor.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            if (song.artworkUrl != null) {
                AsyncImage(
                    model = song.artworkUrl,
                    contentDescription = "${song.title} 的封面",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = mutedColor)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                song.title, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                if (song.album.isNotBlank()) "${song.artist} · ${song.album}" else song.artist,
                color = mutedColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "${song.title} 更多操作", tint = mutedColor)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("播放歌曲") },
                        onClick = { menuExpanded = false; onPlay() }
                    )
                    DropdownMenuItem(
                        text = { Text("复制歌曲链接") },
                        onClick = { menuExpanded = false; onCopyLink() }
                    )
                }
            }
        }
    }
}

private fun sharePlaylist(context: Context, id: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "https://music.163.com/#/playlist?id=$id")
    }
    context.startActivity(Intent.createChooser(intent, "分享网易云歌单"))
}

private fun copyMusicLink(context: Context, kind: String, id: String) {
    val safeKind = if (kind == "playlist") "playlist" else "song"
    val uri = "https://music.163.com/#/$safeKind?id=$id"
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("网易云音乐链接", uri))
    Toast.makeText(context, "已复制链接", Toast.LENGTH_SHORT).show()
}

private fun compactPlays(count: Long): String = when {
    count >= 100_000_000L -> "%.1f亿".format(count / 100_000_000.0)
    count >= 10_000L -> "%.1f万".format(count / 10_000.0)
    else -> count.toString()
}
