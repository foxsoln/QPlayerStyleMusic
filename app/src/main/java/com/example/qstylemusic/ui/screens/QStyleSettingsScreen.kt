package com.example.qstylemusic.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.qstylemusic.data.LocalScanMode
import com.example.qstylemusic.data.LocalScanSettingsStore
import com.example.qstylemusic.plugin.MplugStore
import com.example.qstylemusic.plugin.OnlinePlayableTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * V2.3.7: Settings is the ONLY home for login and plugin management.
 * Uses the app's existing MplugStore and native QR/login modules, not a PC bridge.
 */
@Composable
fun QStyleSettingsScreen(
    modifier: Modifier = Modifier,
    initialTab: String = "plugins",
    darkTheme: Boolean,
    onDarkChange: (Boolean) -> Unit,
    neteaseLoggedIn: Boolean,
    qqLoggedIn: Boolean,
    neteaseName: String?,
    neteaseAvatar: String?,
    qqAccountName: String?,
    qqAccountAvatar: String?,
    segmentedPlaylistsEnabled: Boolean,
    onSegmentedPlaylistsChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onNeteaseLogin: () -> Unit,
    onQqLogin: () -> Unit,
    onNeteaseProfile: () -> Unit,
    onNeteaseLogout: () -> Unit,
    onQqLogout: () -> Unit,
    onOpenStorage: () -> Unit,
    onPlayPluginTrack: (OnlinePlayableTrack) -> Unit
) {
    val colors = remember(darkTheme) {
        SettingsPalette(
            page = if (darkTheme) Color(0xFF1F1B18) else Color(0xFFFFF9F1),
            card = if (darkTheme) Color(0xFF302820) else Color(0xFFF5EBE0),
            selected = if (darkTheme) Color(0xFF594630) else Color(0xFFEDE0CF),
            text = if (darkTheme) Color(0xFFF8F0E5) else Color(0xFF26221E),
            muted = if (darkTheme) Color(0xFFC1B6A9) else Color(0xFF716B61),
            accent = if (darkTheme) Color(0xFFDDB777) else Color(0xFF956614)
        )
    }
    val context = LocalContext.current
    val store = remember(context) { MplugStore(context.applicationContext) }
    val preference = remember(context) {
        context.applicationContext.getSharedPreferences("qstyle_ui", android.content.Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()

    var selectedTab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    var lastSettingsTab by rememberSaveable { mutableStateOf("plugins") }
    var selectedPluginId by rememberSaveable { mutableStateOf<String?>(null) }
    var openSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf(store.list()) }
    var pendingInstall by remember { mutableStateOf<MplugStore.Candidate?>(null) }
    var uninstallId by remember { mutableStateOf<String?>(null) }
    var pendingLogout by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var preferredPluginId by remember { mutableStateOf(preference.getString("preferred_plugin_id", "").orEmpty()) }

    fun reloadPlugins() { installed = store.list() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                working = true
                notice = null
                try {
                    pendingInstall = withContext(Dispatchers.IO) { store.prepareInstall(uri) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    notice = "读取插件失败：${e.localizedMessage.orEmpty()}"
                } finally { working = false }
            }
        }
    }

    BackHandler(enabled = selectedPluginId != null || openSourceId != null) {
        if (openSourceId != null) openSourceId = null
        else {
            selectedPluginId = null
            reloadPlugins()
        }
    }
    BackHandler(enabled = selectedTab == "login" && selectedPluginId == null && openSourceId == null) {
        selectedTab = lastSettingsTab
    }

    // Both plugin detail and its source page are full-size screens inside Settings.
    if (openSourceId != null) {
        Box(modifier.fillMaxSize().background(colors.page)) {
            MplugOnlineScreen(
                pluginId = requireNotNull(openSourceId),
                onBack = { openSourceId = null },
                onPlay = onPlayPluginTrack
            )
        }
    } else if (selectedPluginId != null) {
        val selected = installed.firstOrNull { it.manifest.id == selectedPluginId }
        if (selected != null) {
            PluginDetailPage(
                modifier = modifier,
                plugin = selected,
                colors = colors,
                isPreferred = preferredPluginId == selected.manifest.id,
                onBack = { selectedPluginId = null; reloadPlugins() },
                onEnabledChange = { enabled ->
                    runCatching { store.setEnabled(selected.manifest.id, enabled) }
                        .onSuccess { reloadPlugins() }
                        .onFailure { notice = "切换插件失败：${it.localizedMessage.orEmpty()}" }
                },
                onPreferredClick = {
                    preferredPluginId = if (preferredPluginId == selected.manifest.id) "" else selected.manifest.id
                    preference.edit().putString("preferred_plugin_id", preferredPluginId).apply()
                },
                onOpenSource = { openSourceId = selected.manifest.id },
                onUninstall = { uninstallId = selected.manifest.id },
                notice = notice
            )
        } else {
            // A plugin may have been removed outside this page.
            Column(modifier.fillMaxSize().background(colors.page),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("插件已卸载", color = colors.text)
                Button(onClick = { selectedPluginId = null; reloadPlugins() }) { Text("返回插件列表") }
            }
        }
    } else if (selectedTab == "login") {
        // Separate fullscreen account page, matching the user's second screenshot.
        Column(modifier.fillMaxSize().background(colors.page)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 15.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedTab = lastSettingsTab }) {
                    Icon(Icons.Rounded.ArrowBack, "返回设置", tint = colors.text)
                }
                Text("音源账号", modifier = Modifier.padding(start = 4.dp),
                    style = MaterialTheme.typography.titleLarge, color = colors.text,
                    fontWeight = FontWeight.SemiBold)
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                LoginSettingsTab(
                    colors = colors,
                    neteaseLoggedIn = neteaseLoggedIn,
                    qqLoggedIn = qqLoggedIn,
                    neteaseName = neteaseName,
                    neteaseAvatar = neteaseAvatar,
                    qqName = qqAccountName,
                    qqAvatar = qqAccountAvatar,
                    onNeteaseLogin = onNeteaseLogin,
                    onQqLogin = onQqLogin,
                    onNeteaseProfile = onNeteaseProfile,
                    onLogout = { pendingLogout = it }
                )
            }
        }
    } else {
        Column(modifier.fillMaxSize().background(colors.page)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 15.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, "返回", tint = colors.text)
                }
                Text("设置", modifier = Modifier.weight(1f).padding(start = 4.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, color = colors.text)
                IconButton(onClick = { selectedTab = "about" }) {
                    Icon(Icons.Rounded.Settings, "关于", tint = colors.text)
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val tabs = listOf(
                    "login" to "登录", "display" to "界面", "lyrics" to "歌词",
                    "desktop" to "悬浮歌词", "storage" to "存储",
                    "plugins" to "插件", "about" to "关于"
                )
                tabs.forEach { (key, label) ->
                    val active = selectedTab == key
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(if (active) colors.selected else colors.card)
                            .clickable {
                                if (key == "login") lastSettingsTab = selectedTab
                                selectedTab = key
                                if (key == "plugins") reloadPlugins()
                            }
                            .padding(horizontal = 15.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) colors.accent else colors.text)
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                when (selectedTab) {
                    "plugins" -> {
                        PluginSettingsTab(
                            colors = colors,
                            installed = installed,
                            working = working,
                            notice = notice,
                            onOpen = { id -> notice = null; selectedPluginId = id },
                            onInstall = { picker.launch(arrayOf("*/*")) }
                        )
                    }
                    "display" -> {
                        SectionTitle("外观", colors)
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                .background(colors.card).padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.DarkMode, null, tint = colors.accent)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text("深色模式", color = colors.text,
                                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text("调整整个播放器的明暗主题", color = colors.muted,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = darkTheme, onCheckedChange = onDarkChange)
                        }
                        Spacer(Modifier.height(22.dp))
                        SectionTitle("界面与歌单", colors)
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                .background(colors.card).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("打开细分歌单", color = colors.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(if (segmentedPlaylistsEnabled)
                                    "已启用：分别查看网易云、QQ 音乐和本地歌单。"
                                else "已关闭：默认融合展示所有音源歌单。",
                                    color = colors.muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.width(10.dp))
                            Switch(checked = segmentedPlaylistsEnabled,
                                onCheckedChange = onSegmentedPlaylistsChange)
                        }
                    }
                    "storage" -> StorageSettingsTab(colors, onOpenStorage)
                    "lyrics" -> {
                        SectionTitle("歌词", colors)
                        Spacer(Modifier.height(12.dp))
                        InfoCard("内嵌滚动歌词", "沿用当前播放页的滚动、高亮与进度同步。", colors)
                        Spacer(Modifier.height(9.dp))
                        InfoCard("在线歌词", "继续使用当前网易云和 QQ 音乐歌词模块。", colors)
                    }
                    "desktop" -> {
                        SectionTitle("悬浮歌词", colors)
                        Spacer(Modifier.height(12.dp))
                        InfoCard("暂未启用", "悬浮歌词仍需 Android 悬浮窗权限与后台同步支持。", colors)
                    }
                    "about" -> {
                        SectionTitle("关于", colors)
                        Spacer(Modifier.height(12.dp))
                        InfoCard("QPlayerStyleMusic", "Android · Kotlin · Jetpack Compose · Media3", colors)
                        Spacer(Modifier.height(9.dp))
                        InfoCard("音源插件", "支持从本机导入 .mplug；插件完整性校验不等于开发者身份验证。", colors)
                    }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
    }

    pendingInstall?.let { candidate ->
        AlertDialog(
            onDismissRequest = { if (!working) pendingInstall = null },
            title = { Text("安装音源插件？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(candidate.manifest.name, fontWeight = FontWeight.SemiBold)
                    Text("ID：${candidate.manifest.id}")
                    Text("版本：${candidate.manifest.version}")
                    Text("声明权限：${candidate.manifest.permissions.joinToString().ifBlank { "无" }}")
                    Text("SHA-256：${candidate.manifest.mainSha256.take(16)}…")
                    Text("摘要仅用于完整性校验，不代表已验证开发者身份。请只安装可信插件。",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(enabled = !working, onClick = {
                    pendingInstall = null
                    scope.launch {
                        working = true
                        notice = null
                        try {
                            val installedPlugin = withContext(Dispatchers.IO) { store.install(candidate) }
                            reloadPlugins()
                            notice = "已安装 ${installedPlugin.manifest.name} v${installedPlugin.manifest.version}"
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { notice = "安装失败：${e.localizedMessage.orEmpty()}" }
                        finally { working = false }
                    }
                }) { Text("安装") }
            },
            dismissButton = {
                TextButton(enabled = !working, onClick = { pendingInstall = null }) { Text("取消") }
            }
        )
    }

    uninstallId?.let { id ->
        AlertDialog(
            onDismissRequest = { uninstallId = null },
            title = { Text("移除插件？") },
            text = { Text("确定卸载 ${installed.firstOrNull { it.manifest.id == id }?.manifest?.name ?: id}？账号登录凭证不会因此自动清除。") },
            confirmButton = {
                Button(onClick = {
                    runCatching { store.remove(id) }
                        .onSuccess {
                            if (preferredPluginId == id) {
                                preferredPluginId = ""
                                preference.edit().remove("preferred_plugin_id").apply()
                            }
                            reloadPlugins()
                            selectedPluginId = null
                            notice = "插件已卸载"
                        }
                        .onFailure { notice = "卸载失败：${it.localizedMessage.orEmpty()}" }
                    uninstallId = null
                }) { Text("卸载") }
            },
            dismissButton = { TextButton(onClick = { uninstallId = null }) { Text("取消") } }
        )
    }

    pendingLogout?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingLogout = null },
            title = { Text("退出${if (source == "qq") "QQ 音乐" else "网易云音乐"}？") },
            text = { Text("只清除本机的该音源登录会话，不影响本地收藏与音频文件。") },
            confirmButton = {
                Button(onClick = {
                    if (source == "qq") onQqLogout() else onNeteaseLogout()
                    pendingLogout = null
                }) { Text("退出登录") }
            },
            dismissButton = { TextButton(onClick = { pendingLogout = null }) { Text("取消") } }
        )
    }
}

private data class SettingsPalette(
    val page: Color, val card: Color, val selected: Color,
    val text: Color, val muted: Color, val accent: Color
)

@Composable
private fun LoginSettingsTab(
    colors: SettingsPalette,
    neteaseLoggedIn: Boolean,
    qqLoggedIn: Boolean,
    neteaseName: String?,
    neteaseAvatar: String?,
    qqName: String?,
    qqAvatar: String?,
    onNeteaseLogin: () -> Unit,
    onQqLogin: () -> Unit,
    onNeteaseProfile: () -> Unit,
    onLogout: (String) -> Unit
) {
    val anyLoggedIn = neteaseLoggedIn || qqLoggedIn
    val avatar = when {
        neteaseLoggedIn -> neteaseAvatar
        qqLoggedIn -> qqAvatar
        else -> null
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier.size(96.dp).clip(CircleShape).background(colors.selected),
            contentAlignment = Alignment.Center
        ) {
            if (!avatar.isNullOrBlank()) {
                AsyncImage(
                    model = avatar,
                    contentDescription = "账号头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            } else {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = colors.accent,
                    modifier = Modifier.size(48.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (anyLoggedIn) (if (neteaseLoggedIn) neteaseName else qqName)
                ?.takeIf { it.isNotBlank() } ?: "已登录" else "未登录",
            color = colors.text, style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (anyLoggedIn) "音源账号已经授权，可查看对应歌单" else "登录音源账号后，可以查看和播放有权访问的歌曲",
            color = colors.muted, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(34.dp))
        Text("音源账号", color = colors.text,
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(colors.card).padding(horizontal = 14.dp)) {
            LoginAccountRow(
                source = "网易云音乐", identity = neteaseName,
                avatar = neteaseAvatar, icon = "netease", loggedIn = neteaseLoggedIn,
                colors = colors, onLogin = onNeteaseLogin,
                onLogout = { onLogout("netease") },
                onProfile = onNeteaseProfile
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.muted.copy(alpha = 0.17f)))
            LoginAccountRow(
                source = "QQ 音乐", identity = qqName,
                avatar = qqAvatar, icon = "qq", loggedIn = qqLoggedIn,
                colors = colors, onLogin = onQqLogin,
                onLogout = { onLogout("qq") },
                onProfile = {}
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("点击“登录”将弹出对应的扫码认证界面。登录状态仅保存在本机。",
            color = colors.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LoginAccountRow(
    source: String,
    identity: String?,
    avatar: String?,
    icon: String,
    loggedIn: Boolean,
    colors: SettingsPalette,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onProfile: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(colors.selected).clickable(
                enabled = loggedIn && icon == "netease", onClick = onProfile
            ), contentAlignment = Alignment.Center
        ) {
            if (loggedIn && !avatar.isNullOrBlank()) {
                AsyncImage(model = avatar, contentDescription = "账号头像",
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop)
            } else {
                Icon(if (icon == "qq") Icons.Rounded.MusicNote else Icons.Rounded.Cloud,
                    contentDescription = null, tint = colors.accent)
            }
        }
        Column(Modifier.weight(1f).padding(start = 11.dp)) {
            Text(if (loggedIn) identity?.takeIf { it.isNotBlank() } ?: source else "未登录",
                color = colors.text, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text(source, color = colors.muted, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            if (loggedIn) "退出登录" else "登录",
            color = colors.text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clip(RoundedCornerShape(24.dp))
                .background(colors.selected)
                .clickable(onClick = if (loggedIn) onLogout else onLogin)
                .padding(horizontal = 13.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun PluginSettingsTab(
    colors: SettingsPalette,
    installed: List<MplugStore.Installed>,
    working: Boolean,
    notice: String?,
    onOpen: (String) -> Unit,
    onInstall: () -> Unit
) {
    SectionTitle("已安装", colors)
    Spacer(Modifier.height(10.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(colors.card).padding(horizontal = 14.dp)) {
        if (installed.isEmpty()) {
            Text("暂无已安装插件", color = colors.muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 24.dp))
        }
        installed.forEachIndexed { index, item ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(item.manifest.id) }
                    .padding(vertical = 17.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Extension, contentDescription = null, tint = colors.accent,
                    modifier = Modifier.size(25.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(item.manifest.name, color = colors.text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text("${item.manifest.version} · ${if (item.enabled) "已启用" else "已禁用"}",
                        color = colors.muted, style = MaterialTheme.typography.bodySmall)
                }
                Text("设置", color = colors.text, style = MaterialTheme.typography.labelMedium)
                Icon(Icons.Rounded.ChevronRight, contentDescription = null,
                    tint = colors.muted, modifier = Modifier.size(19.dp))
            }
            if (index != installed.lastIndex) {
                Box(Modifier.fillMaxWidth().height(1.dp)
                    .background(colors.muted.copy(alpha = 0.16f)))
            }
        }
    }
    Spacer(Modifier.height(25.dp))
    SectionTitle("可安装", colors)
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(colors.card).clickable(enabled = !working, onClick = onInstall)
            .padding(horizontal = 16.dp, vertical = 19.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Download, contentDescription = null,
            tint = colors.accent, modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("安装 .mplug 音源插件", color = colors.text,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("从手机选择 .mplug 文件，验证后确认安装。", color = colors.muted,
                style = MaterialTheme.typography.bodySmall)
        }
        if (working) CircularProgressIndicator(Modifier.size(20.dp))
        else Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.muted)
    }
    Spacer(Modifier.height(13.dp))
    Text("仅安装可信插件。安装、启停和卸载均可直接在设置中完成，无需进入旧版插件管理器。",
        color = colors.muted, style = MaterialTheme.typography.bodySmall)
    if (!notice.isNullOrBlank()) {
        Spacer(Modifier.height(10.dp))
        Text(notice, color = if (notice.contains("失败")) MaterialTheme.colorScheme.error else colors.accent,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PluginDetailPage(
    modifier: Modifier,
    plugin: MplugStore.Installed,
    colors: SettingsPalette,
    isPreferred: Boolean,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPreferredClick: () -> Unit,
    onOpenSource: () -> Unit,
    onUninstall: () -> Unit,
    notice: String?
) {
    val manifest = plugin.manifest
    Column(modifier.fillMaxSize().background(colors.page)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 9.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回插件列表", tint = colors.text)
            }
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(15.dp)).background(colors.selected),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Extension, null, tint = colors.accent,
                    modifier = Modifier.size(30.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(manifest.name, color = colors.text,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text("${manifest.id} · ${manifest.version}", color = colors.muted,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text("已安装 · 摘要校验通过（不代表发布者身份验证）",
                style = MaterialTheme.typography.labelSmall, color = colors.accent)
            Spacer(Modifier.height(11.dp))
            Text(manifest.description.ifBlank { "该插件未提供简介。" },
                color = colors.text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text("权限：${manifest.permissions.joinToString(" · ").ifBlank { "无" }}",
                color = colors.muted, style = MaterialTheme.typography.bodySmall)
            Text("功能：${manifest.capabilities.joinToString(" · ").ifBlank { "未声明" }}",
                color = colors.muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(22.dp))

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(colors.card).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 17.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Settings, null, tint = colors.accent)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("启用插件", color = colors.text,
                            style = MaterialTheme.typography.bodyLarge)
                        Text(if (plugin.enabled) "插件当前可以提供内容和功能" else "插件已关闭",
                            color = colors.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = plugin.enabled, onCheckedChange = onEnabledChange)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.muted.copy(alpha = 0.16f)))
                Row(Modifier.fillMaxWidth().clickable(onClick = onPreferredClick).padding(vertical = 17.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = colors.accent)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("首选音源标记", color = colors.text, style = MaterialTheme.typography.bodyLarge)
                        Text(if (isPreferred) "已标记为首选" else "点击设为首选",
                            color = colors.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = isPreferred, onCheckedChange = { onPreferredClick() })
                }
            }
            Spacer(Modifier.height(7.dp))
            Text("首选标记仅保存偏好，不改变现有播放源路由和歌曲授权。",
                color = colors.muted, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(20.dp))

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(colors.card)
                    .clickable(enabled = plugin.enabled && manifest.apiVersion >= 2, onClick = onOpenSource)
                    .padding(17.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Extension, null, tint = colors.accent)
                Column(Modifier.weight(1f).padding(start = 13.dp)) {
                    Text("插件设置与音源功能", color = colors.text,
                        style = MaterialTheme.typography.bodyLarge)
                    Text(if (manifest.apiVersion >= 2)
                        "打开插件已有的音源功能页" else "此版本插件没有独立音源页面",
                        color = colors.muted, style = MaterialTheme.typography.bodySmall)
                }
                if (plugin.enabled && manifest.apiVersion >= 2) {
                    Text("打开", color = colors.text, style = MaterialTheme.typography.labelMedium)
                    Icon(Icons.Rounded.ChevronRight, null, tint = colors.muted)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("插件未声明可配置参数时，不会虚构图示中的 unblock 等设置。",
                color = colors.muted, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(22.dp))
            OutlinedButton(onClick = onUninstall, modifier = Modifier.fillMaxWidth()) {
                Text("移除插件")
            }
            if (!notice.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(notice, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, colors: SettingsPalette) {
    Text(text, modifier = Modifier.padding(start = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = colors.text, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun StorageSettingsTab(colors: SettingsPalette, onOpenStorage: () -> Unit) {
    val context = LocalContext.current
    val rules = remember(context) { LocalScanSettingsStore(context.applicationContext).load() }
    SectionTitle("本地存储", colors)
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(colors.card).clickable(onClick = onOpenStorage).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.FolderOpen, null, tint = colors.accent)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("本地音乐扫描范围", color = colors.text,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "${if (rules.mode == LocalScanMode.SELECTED_ONLY) "仅扫描指定目录" else "排除指定目录"} · ${rules.activeFolders.size} 个目录",
                color = colors.muted, style = MaterialTheme.typography.bodySmall
            )
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = colors.muted)
    }
    Spacer(Modifier.height(12.dp))
    Text("点击进入现有的本地扫描设置，可添加、移除和排除文件夹。",
        color = colors.muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun InfoCard(title: String, description: String, colors: SettingsPalette) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(colors.card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = colors.text, style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold)
        Text(description, color = colors.muted, style = MaterialTheme.typography.bodySmall)
    }
}
