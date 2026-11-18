package com.example.qstylemusic.ui.screens

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.qstylemusic.plugin.MplugOnlineClient
import com.example.qstylemusic.plugin.MplugStore
import com.example.qstylemusic.plugin.auth.QqMusicSession
import com.example.qstylemusic.plugin.OnlinePlayableTrack
import com.example.qstylemusic.plugin.OnlineSongInfo
import kotlinx.coroutines.launch

/** Search, inspect lyrics, and play an accessible stream from an installed .mplug. */
@Composable
fun MplugOnlineScreen(
    pluginId: String,
    onBack: () -> Unit,
    onPlay: (OnlinePlayableTrack) -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { MplugStore(context.applicationContext) }
    val client = remember(context) {
        MplugOnlineClient(store, qqSession = QqMusicSession(context.applicationContext))
    }
    val preferences = remember(context) {
        context.applicationContext.getSharedPreferences("mplug_api_endpoints", 0)
    }
    val scope = rememberCoroutineScope()
    // .mplug package is independent: direct plugin does not need Node.js / a gateway.
    val isDirectNetease = pluginId == "netease.direct"
    val isDirectQq = pluginId == "qqmusic.direct"
    val isDirect = isDirectNetease || isDirectQq
    val sourceName = if (isDirectQq) "QQ 音乐" else "网易云"
    var endpoint by remember(pluginId) {
        mutableStateOf(when {
            isDirectQq -> "https://u.y.qq.com"
            isDirectNetease -> "https://music.163.com"
            else -> preferences.getString(pluginId, "http://127.0.0.1:3000").orEmpty()
        })
    }
    var keyword by remember(pluginId) { mutableStateOf("") }
    var playlistId by remember(pluginId) { mutableStateOf("") }
    var songResults by remember(pluginId) { mutableStateOf<List<OnlineSongInfo>>(emptyList()) }
    var info by remember(pluginId) {
        mutableStateOf(when {
            isDirectQq -> "QQ 音乐手机直连：无需电脑。可以搜索歌曲、查看 LRC、读取公开歌单；匿名播放权限有限。"
            isDirectNetease -> "直连模式：无需 Windows、Node.js 或 API 地址。可先搜索歌曲、查看歌词。在线播放取决于网易云接口和授权。"
            else -> "请先配置自己的网易云 API 兼容服务地址，再搜索。"
        })
    }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    var showQrLogin by remember { mutableStateOf(false) }
    var showAccount by remember(pluginId) { mutableStateOf(false) }
    var showQqLogin by remember(pluginId) { mutableStateOf(false) }

    if (showQqLogin && isDirectQq) {
        QqMusicLoginScreen(
            onBack = { showQqLogin = false },
            onSessionSaved = { showQqLogin = false }
        )
        return
    }

    // NetEase account pages always use the native, encrypted PC QR session.
    // Neither netease.direct nor netease.source receives the Cookie through QuickJS.
    if (showAccount) {
        NeteaseAccountScreen(onBack = { showAccount = false })
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回插件管理")
            }
            Text("${sourceName}音源", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
        }
        Text(
            when {
                isDirectQq -> "QQ 音乐直连：手机 HTTPS 查询，无需 Windows。账号可在「我的 → QQ 音乐」登录；会话只由 Kotlin 宿主附加，不会交给插件。能否播放取决于 QQ 音乐授权。"
                isDirectNetease -> "网易云直连：搜索、公开歌单和歌词通过手机 HTTPS 请求完成，不需要电脑。原扫码登录保留在旧版网易云音源中；直连插件不会读取其 Cookie。"
                else -> "网易云扫码登录直接连接网易云 HTTPS，不需要 API 服务；搜索、歌词和播放仍使用下面配置的 API 服务。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        if (isDirectQq) {
            Button(onClick = { showQqLogin = true }) { Text("QQ / 微信扫码登录（官网）") }
            Spacer(Modifier.height(8.dp))
        }
        if (pluginId == "netease.source" || isDirectNetease) {
            Button(onClick = { showAccount = true }) { Text("我的网易云 · 个人主页 / 歌单") }
            Spacer(Modifier.height(8.dp))
        }
        if (pluginId == "netease.source") {
            Button(onClick = { showQrLogin = true }) { Text("网易云 App 扫码登录") }
            Text("登录后不会绕过会员或版权权限；后续的 API 服务尚不会自动获得本机登录 Cookie。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
        if (isDirect) {
            Text(if (isDirectQq)
                    "连接地址：https://u.y.qq.com / c.y.qq.com（固定，使用 QQ 音乐网页接口）"
                 else "连接地址：https://music.163.com（固定，使用官方域名上的非公开网页接口）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        } else {
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API 服务地址") },
                singleLine = true,
                supportingText = { Text("真机 USB 调试可用 http://127.0.0.1:3000 + adb reverse；远程服务必须使用 HTTPS") }
            )
            Spacer(Modifier.height(6.dp))
        }
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("搜索${sourceName}歌曲") },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = !busy && keyword.isNotBlank(), onClick = {
                if (!isDirect) preferences.edit().putString(pluginId, endpoint.trim()).apply()
                scope.launch {
                    busy = true
                    info = "搜索中…"
                    try {
                        songResults = client.search(pluginId, keyword.trim(), endpoint.trim())
                        info = "获取到 ${songResults.size} 首歌曲。点击歌曲可查看歌词或尝试播放。"
                    } catch (t: Exception) {
                        songResults = emptyList()
                        info = "搜索失败：${t.localizedMessage.orEmpty()}"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "正在查询…" else "搜索") }
            if (!isDirect) {
                OutlinedButton(enabled = !busy, onClick = {
                    preferences.edit().putString(pluginId, endpoint.trim()).apply()
                    info = "地址已保存到本机；首次使用请先运行 API 服务。"
                }) { Text("保存地址") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = playlistId,
                onValueChange = { playlistId = it },
                modifier = Modifier.weight(1f),
                label = { Text("歌单 ID（可选）") },
                singleLine = true
            )
            OutlinedButton(enabled = !busy && playlistId.matches(Regex("[0-9]{1,19}")), onClick = {
                if (!isDirect) preferences.edit().putString(pluginId, endpoint.trim()).apply()
                scope.launch {
                    busy = true
                    info = "正在读取公开歌单…"
                    try {
                        val (name, songs) = client.playlist(pluginId, playlistId, endpoint.trim())
                        songResults = songs
                        info = "歌单：$name，共返回 ${songs.size} 首"
                    } catch (t: Exception) {
                        info = "歌单读取失败：${t.localizedMessage.orEmpty()}"
                    } finally {
                        busy = false
                    }
                }
            }) { Text("打开") }
        }
        Spacer(Modifier.height(10.dp))
        Text(info, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(songResults, key = { it.id }) { song ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(song.title, style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(enabled = !busy, onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        preview = client.lyrics(pluginId, song.id, endpoint.trim())
                                            ?: "该歌曲暂时没有可用的 LRC 歌词。"
                                    } catch (t: Exception) {
                                        preview = "歌词读取失败：${t.localizedMessage.orEmpty()}"
                                    } finally {
                                        busy = false
                                    }
                                }
                            }) { Text("歌词") }
                            Button(enabled = !busy, onClick = {
                                scope.launch {
                                    busy = true
                                    info = "正在获取「${song.title}」的授权播放地址…"
                                    try {
                                        val url = client.stream(pluginId, song.id, endpoint.trim())
                                        val lrc = runCatching {
                                            client.lyrics(pluginId, song.id, endpoint.trim())
                                        }.getOrNull()
                                        onPlay(
                                            OnlinePlayableTrack(
                                                mediaId = "${if (isDirectQq) "qqmusic" else "netease"}:song:${song.id}",
                                                title = song.title,
                                                artist = song.artist,
                                                artworkUrl = song.artworkUrl,
                                                streamUrl = url,
                                                lyrics = lrc
                                            )
                                        )
                                        info = "已提交 Media3 播放：${song.title}"
                                    } catch (t: Exception) {
                                        info = "无法播放：${t.localizedMessage.orEmpty()}"
                                    } finally {
                                        busy = false
                                    }
                                }
                            }) { Text("播放") }
                        }
                    }
                }
            }
        }
    }

    if (showQrLogin) {
        MplugQrLoginDialog(pluginId = pluginId, onDismiss = { showQrLogin = false })
    }

    preview?.let { lyrics ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("在线 LRC 歌词") },
            text = {
                androidx.compose.foundation.layout.Box(
                    Modifier.height(360.dp).fillMaxWidth()
                ) {
                    val scroll = androidx.compose.foundation.rememberScrollState()
                    Text(
                        lyrics,
                        modifier = Modifier.fillMaxWidth()
                            .verticalScroll(scroll),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(onClick = { preview = null }) { Text("关闭") }
            }
        )
    }
}

