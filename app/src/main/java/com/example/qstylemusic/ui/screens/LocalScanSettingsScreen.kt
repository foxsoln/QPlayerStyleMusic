package com.example.qstylemusic.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.qstylemusic.data.LocalScanFolderParser
import com.example.qstylemusic.data.LocalScanMode
import com.example.qstylemusic.data.LocalScanSettings
import com.example.qstylemusic.data.LocalScanSettingsStore

/** Settings > local music scan scope. Changes persist immediately and rescan on exit. */
@Composable
fun LocalScanSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { LocalScanSettingsStore(context.applicationContext) }
    var rules by remember { mutableStateOf(store.load()) }
    var manualPath by rememberSaveable { mutableStateOf("") }

    fun save(next: LocalScanSettings) {
        rules = next
        store.save(next)
    }

    fun addFromText(text: String) {
        val folder = LocalScanFolderParser.fromManualPath(text)
        if (folder == null) {
            Toast.makeText(context, "请输入有效的相对目录，例如 Music/我的歌曲", Toast.LENGTH_LONG).show()
        } else {
            save(rules.withFolder(folder))
            manualPath = ""
        }
    }

    val chooseFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val folder = LocalScanFolderParser.fromTreeUri(uri)
            if (folder == null) {
                Toast.makeText(
                    context,
                    "无法从此文件管理器识别存储路径，请使用下方手动添加相对路径",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                save(rules.withFolder(folder))
            }
        }
    }

    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "返回我的") }
            Spacer(Modifier.width(8.dp))
            Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("本地文件扫描范围", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "可指定扫描一个或多个文件夹，也可排除一个或多个文件夹。\n" +
                            "所选文件夹包含其所有子文件夹，只影响本地音乐，不影响网易云歌单。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ScanModeRow(
                        selected = rules.mode == LocalScanMode.SELECTED_ONLY,
                        title = "只扫描指定文件夹",
                        detail = "仅显示下方选中目录中的本地音乐",
                        onClick = { save(rules.copy(mode = LocalScanMode.SELECTED_ONLY)) }
                    )
                    ScanModeRow(
                        selected = rules.mode == LocalScanMode.EXCLUDE_SELECTED,
                        title = "排除指定文件夹",
                        detail = "扫描其他音乐，忽略下方排除目录",
                        onClick = { save(rules.copy(mode = LocalScanMode.EXCLUDE_SELECTED)) }
                    )
                }
            }
            item {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (rules.mode == LocalScanMode.SELECTED_ONLY) "扫描的文件夹" else "排除的文件夹",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (rules.activeFolders.isNotEmpty()) {
                        TextButton(onClick = { save(rules.clearActive()) }) { Text("清空列表") }
                    }
                }
            }
            if (rules.activeFolders.isEmpty()) {
                item {
                    Text(
                        if (rules.mode == LocalScanMode.SELECTED_ONLY)
                            "当前没有选择文件夹。此模式下将不会显示任何本地音乐，请先添加目录。"
                        else
                            "当前没有排除目录。默认仍显示所有已被系统索引的本地音乐。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                items(
                    items = rules.activeFolders,
                    key = { "${it.volumeName}:${it.relativePath}" }
                ) { folder ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                        Text(
                            folder.displayName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        IconButton(onClick = { save(rules.withoutFolder(folder)) }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "移除目录")
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { chooseFolder.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.FolderOpen, null)
                    Spacer(Modifier.width(8.dp))
                    Text("选择文件夹（可重复添加）")
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("也可以手动添加目录", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = manualPath,
                        onValueChange = { manualPath = it },
                        label = { Text("相对路径") },
                        placeholder = { Text("例如 Music/收藏 或 Download") },
                        supportingText = { Text("内部存储按 Music/文件夹 填写；存储卡可填 卡卷ID:Music/文件夹") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedButton(
                        onClick = { addFromText(manualPath) },
                        enabled = manualPath.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("添加这个路径") }
                }
            }
            item {
                Text(
                    "提示：Android 11 及以上不允许在系统目录选择器中直接选取内部存储根目录或 Download 根目录；" +
                        "可使用上方的“手动添加目录”输入 Download。" +
                        "仅对 Android 媒体库已索引的音频生效，不会访问其他应用的私有文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
        ) { Text("完成并重新扫描") }
    }
}

@Composable
private fun ScanModeRow(
    selected: Boolean,
    title: String,
    detail: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
