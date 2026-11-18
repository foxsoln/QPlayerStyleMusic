package com.example.qstylemusic.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.qstylemusic.data.Song
import com.example.qstylemusic.plugin.OnlinePlayableTrack
import com.example.qstylemusic.plugin.auth.NeteaseAccountClient
import com.example.qstylemusic.plugin.auth.NeteaseAccountPlaylist
import com.example.qstylemusic.plugin.auth.NeteaseAccountPlaylistDetail
import com.example.qstylemusic.plugin.auth.QqMusicAccountClient
import com.example.qstylemusic.playback.OnlineFavorite

/**
 * V2.3.4 细分歌单：
 * 顶部仅保留 网易云 / QQ 音乐 / 本地歌单 三个切换项。
 * 三种音源全部复用融合歌单的双列正方形卡片和直达详情的回调。
 * 不再显示“我的歌单 / 全部 / 创建 / 收藏”筛选，也没有深色模式开关。
 */
@Composable
fun MineScreen(
    songs: List<Song>,
    favorites: Set<Long>,
    recentIds: List<Long>,
    darkTheme: Boolean,
    onDarkChange: (Boolean) -> Unit,
    onPlay: (Song) -> Unit,
    onFavorite: (Long) -> Unit,
    onPlayNetease: (NeteaseAccountPlaylistDetail, Int) -> Unit,
    onOpenNeteaseLogin: () -> Unit,
    onOpenNeteasePlaylist: ((NeteaseAccountPlaylist) -> Unit)? = null,
    onOpenQqLogin: () -> Unit,
    onPlayQqTrack: (OnlinePlayableTrack) -> Unit,
    onOpenQqPlaylist: ((QqMusicAccountClient.Playlist) -> Unit)? = null,
    onOpenLocalPlaylist: ((String) -> Unit)? = null,
    accountRefreshKey: Int = 0,
    onlineFavorites: List<OnlineFavorite> = emptyList(),
    initialSource: String = "netease",
    neteaseLoggedIn: Boolean? = null,
    qqLoggedIn: Boolean? = null,
    onSourceChange: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val neteaseClient = remember(context) { NeteaseAccountClient(context.applicationContext) }
    val qqClient = remember(context) { QqMusicAccountClient(context.applicationContext) }
    var selectedSource by remember(initialSource) {
        mutableStateOf(initialSource.takeIf { it in setOf("netease", "qq", "local") } ?: "netease")
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedSource == "netease",
                onClick = {
                    selectedSource = "netease"
                    onSourceChange("netease")
                },
                label = { Text("网易云") }
            )
            FilterChip(
                selected = selectedSource == "qq",
                onClick = {
                    selectedSource = "qq"
                    onSourceChange("qq")
                },
                label = { Text("QQ 音乐") }
            )
            FilterChip(
                selected = selectedSource == "local",
                onClick = {
                    selectedSource = "local"
                    onSourceChange("local")
                },
                label = { Text("本地歌单") }
            )
        }

        Text(
            text = "歌单",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, end = 12.dp, bottom = 12.dp)
        )

        when (selectedSource) {
            "local" -> LocalPlaylistCards(
                songs = songs,
                favorites = favorites,
                recentIds = recentIds,
                darkTheme = darkTheme,
                onlineFavorites = onlineFavorites,
                onOpenLocalPlaylist = { kind -> onOpenLocalPlaylist?.invoke(kind) }
            )
            "qq" -> MergedPlaylistGrid(
                darkTheme = darkTheme,
                songs = songs,
                favorites = favorites,
                neteaseLoggedIn = neteaseLoggedIn ?: neteaseClient.hasLocalSession,
                qqLoggedIn = qqLoggedIn ?: qqClient.hasLocalSession,
                accountRefreshKey = accountRefreshKey,
                onOpenNeteasePlaylist = { playlist ->
                    if (onOpenNeteasePlaylist != null) onOpenNeteasePlaylist(playlist)
                    else onOpenNeteaseLogin()
                },
                onOpenQqPlaylist = { playlist ->
                    if (onOpenQqPlaylist != null) onOpenQqPlaylist(playlist)
                    else onOpenQqLogin()
                },
                onOpenLocalPlaylist = { kind -> onOpenLocalPlaylist?.invoke(kind) },
                sourceFilter = "qq"
            )
            else -> MergedPlaylistGrid(
                darkTheme = darkTheme,
                songs = songs,
                favorites = favorites,
                neteaseLoggedIn = neteaseLoggedIn ?: neteaseClient.hasLocalSession,
                qqLoggedIn = qqLoggedIn ?: qqClient.hasLocalSession,
                accountRefreshKey = accountRefreshKey,
                onOpenNeteasePlaylist = { playlist ->
                    if (onOpenNeteasePlaylist != null) onOpenNeteasePlaylist(playlist)
                    else onOpenNeteaseLogin()
                },
                onOpenQqPlaylist = { playlist ->
                    if (onOpenQqPlaylist != null) onOpenQqPlaylist(playlist)
                    else onOpenQqLogin()
                },
                onOpenLocalPlaylist = { kind -> onOpenLocalPlaylist?.invoke(kind) },
                sourceFilter = "netease"
            )
        }
    }
}
