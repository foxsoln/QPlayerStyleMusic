package com.example.qstylemusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.qstylemusic.data.Song
import com.example.qstylemusic.plugin.OnlinePlayableTrack
import com.example.qstylemusic.plugin.auth.NeteaseAccountClient
import com.example.qstylemusic.plugin.auth.NeteaseAccountPlaylist
import com.example.qstylemusic.plugin.auth.NeteaseAccountPlaylistDetail
import com.example.qstylemusic.plugin.auth.QqMusicAccountClient
import com.example.qstylemusic.playback.OnlineFavorite
import kotlinx.coroutines.CancellationException

/**
 * Default = all playlist covers together, card click goes straight to that playlist.
 * Settings: "打开细分歌单" switches to the existing source-specific browsing UI.
 * Source accounts are managed in Settings -> 登录.
 */
@Composable
fun MineHomeScreen(
    songs: List<Song>,
    favorites: Set<Long>,
    recentIds: List<Long>,
    darkTheme: Boolean,
    onDarkChange: (Boolean) -> Unit,
    neteaseLoggedIn: Boolean,
    qqLoggedIn: Boolean,
    neteaseName: String?,
    neteaseAvatar: String?,
    showLibrary: Boolean,
    librarySource: String,
    onShowLibraryChange: (Boolean) -> Unit,
    onLibrarySourceChange: (String) -> Unit,
    onExit: () -> Unit,
    onOpenSettings: (String) -> Unit,
    onRefresh: () -> Unit,
    onPlay: (Song) -> Unit,
    onFavorite: (Long) -> Unit,
    onPlayNetease: (NeteaseAccountPlaylistDetail, Int) -> Unit,
    onOpenNeteasePlaylist: (NeteaseAccountPlaylist) -> Unit,
    onPlayQqTrack: (OnlinePlayableTrack) -> Unit,
    onOpenQqPlaylist: (QqMusicAccountClient.Playlist) -> Unit,
    onOpenLocalPlaylist: (String) -> Unit,
    accountRefreshKey: Int,
    segmentedPlaylistsEnabled: Boolean,
    onlineFavorites: List<OnlineFavorite> = emptyList()
) {
    val page = MaterialTheme.colorScheme.background
    val ink = MaterialTheme.colorScheme.onBackground
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    var moreExpanded by remember { mutableStateOf(false) }
    var activeTab by rememberSaveable { mutableStateOf("mine") }

    Column(Modifier.fillMaxSize().background(page)) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("我的", color = ink, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineMedium)
            Box {
                IconButton(onClick = { moreExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, "更多", tint = ink)
                }
                DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                    DropdownMenuItem(text = { Text("设置") }, onClick = {
                        moreExpanded = false
                        onOpenSettings("display")
                    })
                    DropdownMenuItem(text = { Text("刷新") }, onClick = {
                        moreExpanded = false
                        onRefresh()
                    })
                }
            }
        }

        if (segmentedPlaylistsEnabled) {
            MineScreen(
                songs = songs,
                favorites = favorites,
                recentIds = recentIds,
                darkTheme = darkTheme,
                onDarkChange = onDarkChange,
                onPlay = onPlay,
                onFavorite = onFavorite,
                onPlayNetease = onPlayNetease,
                onOpenNeteaseLogin = { onOpenSettings("login") },
                onOpenNeteasePlaylist = onOpenNeteasePlaylist,
                onOpenQqLogin = { onOpenSettings("login") },
                onPlayQqTrack = onPlayQqTrack,
                onOpenQqPlaylist = onOpenQqPlaylist,
                onOpenLocalPlaylist = onOpenLocalPlaylist,
                accountRefreshKey = accountRefreshKey,
                onlineFavorites = onlineFavorites,
                initialSource = librarySource,
                neteaseLoggedIn = neteaseLoggedIn,
                qqLoggedIn = qqLoggedIn,
                onSourceChange = onLibrarySourceChange
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = activeTab == "mine", onClick = { activeTab = "mine" },
                    label = { Text("我的歌单") }
                )
                FilterChip(
                    selected = activeTab == "local", onClick = { activeTab = "local" },
                    label = { Text("本地歌单") }
                )
            }
            if (activeTab == "mine") {
                MergedPlaylistGrid(
                    darkTheme = darkTheme,
                    songs = songs,
                    favorites = favorites,
                    onlineFavorites = onlineFavorites,
                    neteaseLoggedIn = neteaseLoggedIn,
                    qqLoggedIn = qqLoggedIn,
                    accountRefreshKey = accountRefreshKey,
                    onOpenNeteasePlaylist = onOpenNeteasePlaylist,
                    onOpenQqPlaylist = onOpenQqPlaylist,
                    onOpenLocalPlaylist = onOpenLocalPlaylist
                )
            } else {
                LocalPlaylistCards(
                    songs = songs,
                    favorites = favorites,
                    recentIds = recentIds,
                    darkTheme = darkTheme,
                    onlineFavorites = onlineFavorites,
                    onOpenLocalPlaylist = onOpenLocalPlaylist
                )
            }
        }
    }
}

private data class PlaylistCardData(
    val key: String,
    val title: String,
    val subtitle: String,
    val source: String,
    val artwork: Any?,
    val onOpen: () -> Unit
)

@Composable
internal fun MergedPlaylistGrid(
    darkTheme: Boolean,
    songs: List<Song>,
    favorites: Set<Long>,
    onlineFavorites: List<OnlineFavorite> = emptyList(),
    neteaseLoggedIn: Boolean,
    qqLoggedIn: Boolean,
    accountRefreshKey: Int,
    onOpenNeteasePlaylist: (NeteaseAccountPlaylist) -> Unit,
    onOpenQqPlaylist: (QqMusicAccountClient.Playlist) -> Unit,
    onOpenLocalPlaylist: (String) -> Unit,
    sourceFilter: String? = null
) {
    val context = LocalContext.current
    val netease = remember(context) { NeteaseAccountClient(context.applicationContext) }
    val qq = remember(context) { QqMusicAccountClient(context.applicationContext) }
    var loading by remember { mutableStateOf(true) }
    var neteasePlaylists by remember { mutableStateOf<List<NeteaseAccountPlaylist>>(emptyList()) }
    var qqPlaylists by remember { mutableStateOf<List<QqMusicAccountClient.Playlist>>(emptyList()) }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(accountRefreshKey, neteaseLoggedIn, qqLoggedIn, sourceFilter) {
        loading = true
        warnings = emptyList()
        neteasePlaylists = emptyList()
        qqPlaylists = emptyList()
        val errors = mutableListOf<String>()
        if (neteaseLoggedIn && (sourceFilter == null || sourceFilter == "netease")) {
            try { neteasePlaylists = netease.myLibrary().playlists }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors += "网易云：${e.localizedMessage ?: "读取失败"}" }
        }
        if (qqLoggedIn && (sourceFilter == null || sourceFilter == "qq")) {
            try { qqPlaylists = qq.myPlaylists() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors += "QQ 音乐：${e.localizedMessage ?: "读取失败"}" }
        }
        warnings = errors
        loading = false
    }

    // A cloud playlist must represent a real returned playlist ID.
    // Do not fabricate a 318-song local '我喜欢' card from QQ login metadata.
    val cloudCards = mutableListOf<PlaylistCardData>()
    if (sourceFilter == null || sourceFilter == "qq") qqPlaylists.forEach { pl ->
        cloudCards += PlaylistCardData(
            key = "qq_${pl.id}",
            title = pl.name,
            subtitle = "${pl.songCount} 首歌曲 · QQ 音乐",
            source = "QQ 音乐",
            artwork = pl.coverUrl,
            onOpen = { onOpenQqPlaylist(pl) }
        )
    }
    if (sourceFilter == null || sourceFilter == "netease") neteasePlaylists.forEach { pl ->
        cloudCards += PlaylistCardData(
            key = "netease_${pl.id}",
            title = pl.name,
            subtitle = "${pl.trackCount} 首歌曲 · 网易云",
            source = "网易云",
            artwork = pl.artworkUrl,
            onOpen = { onOpenNeteasePlaylist(pl) }
        )
    }
    val likedLocal = songs.filter { it.id in favorites }
    if (sourceFilter == null && (likedLocal.isNotEmpty() || onlineFavorites.isNotEmpty())) {
        cloudCards += PlaylistCardData(
            key = "local_favorites", title = "我喜欢",
            subtitle = "${likedLocal.size + onlineFavorites.size} 首歌曲 · 本机收藏",
            source = "本地", artwork = likedLocal.firstOrNull()?.artworkUri,
            onOpen = { onOpenLocalPlaylist("favorites") }
        )
    }
    // Show real '我喜欢' playlists first; others preserve their source order.
    val cards = cloudCards.sortedWith(compareBy<PlaylistCardData> {
        if (it.title.contains("我喜欢", ignoreCase = true)) 0 else 1
    })
    Column(Modifier.fillMaxSize()) {
        warnings.forEach { warning ->
            Text("歌单同步部分失败：$warning", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (cards.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopStart) {
                Text("当前没有可显示的歌单，请在右上角“设置”→“登录”中连接音源。", color = secondary)
            }
        } else {
            PlaylistCardGrid(cards, darkTheme)
        }
    }
}

@Composable
internal fun LocalPlaylistCards(
    songs: List<Song>,
    favorites: Set<Long>,
    recentIds: List<Long>,
    darkTheme: Boolean,
    onlineFavorites: List<OnlineFavorite> = emptyList(),
    onOpenLocalPlaylist: (String) -> Unit
) {
    val liked = songs.filter { it.id in favorites }
    val recent = recentIds.mapNotNull { id -> songs.find { it.id == id } }
    val cards = listOf(
        PlaylistCardData(
            key = "favorites", title = "我喜欢", subtitle = "${liked.size + onlineFavorites.size} 首歌曲 · 本机收藏",
            source = "本地", artwork = liked.firstOrNull()?.artworkUri,
            onOpen = { onOpenLocalPlaylist("favorites") }
        ),
        PlaylistCardData(
            key = "recent", title = "最近播放", subtitle = "${recent.size} 首歌曲 · 本地",
            source = "本地", artwork = recent.firstOrNull()?.artworkUri,
            onOpen = { onOpenLocalPlaylist("recent") }
        )
    )
    PlaylistCardGrid(cards, darkTheme)
}

@Composable
private fun PlaylistCardGrid(cards: List<PlaylistCardData>, darkTheme: Boolean) {
    val ink = MaterialTheme.colorScheme.onBackground
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val tile = MaterialTheme.colorScheme.surfaceContainer
    val accent = MaterialTheme.colorScheme.primary
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(cards, key = { it.key }) { pl ->
            Column(Modifier.fillMaxWidth().clickable(onClick = pl.onOpen)) {
                val isLocalFavorite = pl.key == "favorites" || pl.key == "local_favorites"
                val isLocalRecent = pl.key == "recent"
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isLocalFavorite) Color(0xFFFF9FA1) else tile),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLocalFavorite) {
                        Icon(Icons.Rounded.Favorite, null, tint = Color.White,
                            modifier = Modifier.size(76.dp))
                    } else if (pl.artwork != null) {
                        AsyncImage(
                            model = pl.artwork,
                            contentDescription = pl.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            if (isLocalRecent) Icons.Rounded.History else Icons.Rounded.MusicNote,
                            null, tint = accent, modifier = Modifier.size(50.dp)
                        )
                    }
                }
                Text(pl.title, color = ink, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp))
                Text(pl.subtitle, color = secondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
