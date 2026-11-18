package com.example.qstylemusic.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.example.qstylemusic.plugin.MplugJsRuntime
import com.example.qstylemusic.plugin.MplugStore
import com.example.qstylemusic.plugin.OnlinePlayableTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Plugin manager for our custom .mplug v1 format (NOT QPlayer's .qplug). */
@Composable
fun MplugManagerScreen(
    onBack: () -> Unit,
    onPlayOnline: (OnlinePlayableTrack) -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { MplugStore(context.applicationContext) }
    val runtime = remember { MplugJsRuntime() }
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(store.list()) }
    var pending by remember { mutableStateOf<MplugStore.Candidate?>(null) }
    var uninstallId by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf("请选择 .mplug 文件安装。可以先使用补丁附带的 demo.local.mplug 测试。") }
    var busy by remember { mutableStateOf(false) }
    var openSource by remember { mutableStateOf<String?>(null) }

    fun refresh() { installed = store.list() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                try {
                    pending = withContext(Dispatchers.IO) { store.prepareInstall(uri) }
                } catch (t: Exception) {
                    result = "读取插件失败：${t.localizedMessage.orEmpty()}"
                } finally {
                    busy = false
                }
            }
        }
    }

    openSource?.let { id ->
        MplugOnlineScreen(
            pluginId = id,
            onBack = { openSource = null },
            onPlay = onPlayOnline
        )
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Icon(Icons.Rounded.Extension, contentDescription = null)
            Text("音源插件管理", modifier = Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        Text("V0.7 · .mplug 离线插件 + 受控在线音源", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(
            enabled = !busy,
            onClick = { picker.launch(arrayOf("*/*")) }
        ) { Text(if (busy) "正在检查插件…" else "从文件安装 .mplug") }
        Spacer(Modifier.height(10.dp))
        Text("网络插件只可连接指定的 API 服务；新版网易云插件还支持直接扫码登录。会话加密保存在宿主中。请勿安装未知来源插件：当前没有发布者签名和独立进程隔离。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Text("已安装插件（${installed.size}）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (installed.isEmpty()) {
            Text("还没有插件。安装示例插件后，即可测试 JS 是否成功运行。",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        installed.forEach { plugin ->
            Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(plugin.manifest.name, style = MaterialTheme.typography.titleMedium)
                    Text("${plugin.manifest.id}  ·  v${plugin.manifest.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (plugin.manifest.description.isNotBlank()) {
                        Text(plugin.manifest.description, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (plugin.enabled) "已启用" else "已禁用")
                        Switch(checked = plugin.enabled, onCheckedChange = { enabled ->
                            runCatching { store.setEnabled(plugin.manifest.id, enabled) }
                                .onSuccess { refresh() }
                                .onFailure { result = "切换失败：${it.localizedMessage.orEmpty()}" }
                        })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (plugin.manifest.apiVersion >= 2) {
                            OutlinedButton(enabled = !busy && plugin.enabled, onClick = {
                                openSource = plugin.manifest.id
                            }) { Text("打开音源") }
                        } else {
                            OutlinedButton(enabled = !busy && plugin.enabled, onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        val archive = withContext(Dispatchers.IO) {
                                            store.loadEnabled(plugin.manifest.id)
                                        }
                                        val info = runtime.call(archive, "getInfo")
                                        val search = runtime.call(archive, "search", JSONObject().put("keyword", "测试"))
                                        result = "${plugin.manifest.name} 运行成功\ngetInfo: $info\nsearch: $search"
                                    } catch (t: Exception) {
                                        result = "执行失败：${t.localizedMessage.orEmpty()}"
                                    } finally {
                                        busy = false
                                    }
                                }
                            }) { Text("运行测试") }
                        }
                        OutlinedButton(onClick = { uninstallId = plugin.manifest.id }) {
                            Text("卸载")
                        }
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("运行结果", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(result, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }

    pending?.let { candidate ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("确认安装插件？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(candidate.manifest.name)
                    Text("ID：${candidate.manifest.id}")
                    Text("版本：${candidate.manifest.version}")
                    Text("能力：${candidate.manifest.capabilities.joinToString()}")
                    Text("API：v${candidate.manifest.apiVersion}")
                    Text("权限：${candidate.manifest.permissions.joinToString().ifBlank { "无" }}")
                    if (candidate.manifest.permissions.contains("http.request")) {
                        Text("该插件可以查询你指定的音源 API 服务器。请确认服务器可信。")
                    }
                    Text("SHA-256：${candidate.manifest.mainSha256.take(16)}…")
                    Text("这是完整性校验，不是作者身份认证。只安装可信文件。",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = {
                    pending = null
                    scope.launch {
                        busy = true
                        try {
                            val item = withContext(Dispatchers.IO) { store.install(candidate) }
                            refresh()
                            result = "已安装 ${item.manifest.name} v${item.manifest.version}"
                        } catch (t: Exception) {
                            result = "安装失败：${t.localizedMessage.orEmpty()}"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("安装") }
            },
            dismissButton = { OutlinedButton(onClick = { pending = null }) { Text("取消") } }
        )
    }

    uninstallId?.let { id ->
        AlertDialog(
            onDismissRequest = { uninstallId = null },
            title = { Text("卸载插件？") },
            text = { Text("确定要卸载 $id 吗？") },
            confirmButton = {
                Button(onClick = {
                    runCatching { store.remove(id) }
                        .onSuccess { refresh(); result = "已卸载 $id" }
                        .onFailure { result = "卸载失败：${it.localizedMessage.orEmpty()}" }
                    uninstallId = null
                }) { Text("卸载") }
            },
            dismissButton = { OutlinedButton(onClick = { uninstallId = null }) { Text("取消") } }
        )
    }
}
