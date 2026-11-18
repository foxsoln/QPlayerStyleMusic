package com.example.qstylemusic.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.qstylemusic.plugin.auth.NeteaseAccountProfile

/** Native account detail dialog. Never holds or displays login tokens. */
@Composable
fun NeteaseProfileDialog(
    profile: NeteaseAccountProfile?,
    loading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("我的网易云资料") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    loading -> CircularProgressIndicator()
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                    profile != null -> {
                        AsyncImage(
                            model = profile.avatarUrl,
                            contentDescription = "网易云头像",
                            modifier = Modifier.size(88.dp).clip(CircleShape)
                        )
                        Text(profile.nickname, style = MaterialTheme.typography.titleLarge)
                        Text("网易云 UID：${profile.userId}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("资料来自本机当前已授权的网易云账号。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> Text("尚未读取到账号资料")
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = onRefresh) { Text("刷新资料") } }
    )
}
