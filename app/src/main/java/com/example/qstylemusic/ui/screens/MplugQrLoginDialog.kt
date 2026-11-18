package com.example.qstylemusic.ui.screens

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.qstylemusic.plugin.auth.MplugQrLoginClient
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext

/** Direct QR login using NetEase's official HTTPS login host, not an API gateway. */
@Composable
fun MplugQrLoginDialog(pluginId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val auth = remember(context) { MplugQrLoginClient(context.applicationContext) }
    val clipboard = LocalClipboardManager.current
    var serial by remember(pluginId) { mutableIntStateOf(0) }
    var signedIn by remember(pluginId) { mutableStateOf(auth.loggedIn) }
    var challenge by remember(pluginId) { mutableStateOf<MplugQrLoginClient.Challenge?>(null) }
    var message by remember(pluginId) { mutableStateOf(if (signedIn) "本机已有保存的登录会话（未再次进行联网校验）" else "正在获取登录二维码…") }
    var running by remember(pluginId) { mutableStateOf(false) }

    LaunchedEffect(pluginId, serial) {
        if (signedIn && serial == 0) return@LaunchedEffect
        challenge = null
        signedIn = false
        running = true
        message = "正在创建网易云登录二维码…"
        try {
            val task = auth.begin(pluginId)
            challenge = task
            message = "请用网易云音乐 App 扫码并在 App 内确认"
            val expiresAt = System.currentTimeMillis() + 3 * 60 * 1000L
            while (currentCoroutineContext().isActive && System.currentTimeMillis() < expiresAt) {
                delay(3_000L)
                when (auth.poll(pluginId, task.key)) {
                    MplugQrLoginClient.Status.WAITING -> message = "等待扫码…"
                    MplugQrLoginClient.Status.SCANNED -> message = "已扫描，请在网易云音乐 App 内确认登录"
                    MplugQrLoginClient.Status.EXPIRED -> {
                        challenge = null
                        message = "二维码已过期，点击重新生成"
                        break
                    }
                    MplugQrLoginClient.Status.SUCCESS -> {
                        signedIn = true
                        challenge = null
                        message = "扫码登录成功，会话已加密保存到本机"
                        break
                    }
                }
            }
            if (!signedIn && challenge != null && System.currentTimeMillis() >= expiresAt) {
                challenge = null
                message = "二维码已过期，请重新生成"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "扫码登录失败：${e.localizedMessage.orEmpty()}"
        } finally {
            running = false
        }
    }

    val url = challenge?.url
    val qrImage = remember(url) { url?.let(::createQrCode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("网易云音乐扫码登录") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(message, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium)
                if (qrImage != null && !signedIn) {
                    Image(
                        bitmap = qrImage.asImageBitmap(),
                        contentDescription = "网易云音乐登录二维码",
                        modifier = Modifier.size(240.dp)
                    )
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(url.orEmpty())) }) {
                        Text("复制二维码链接")
                    }
                    Text("手机上显示的二维码，通常需要另一台设备扫码；如果网易云 App 支持从相册识别，也可使用截图。",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (signedIn) {
                    Text("登录凭据仅保存在应用私有存储，并由 Android Keystore 加密保护；不会交给 JavaScript 插件或自建 API 服务。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        auth.logoutLocal()
                        signedIn = false
                        serial++
                    }) { Text("退出本机账号") }
                }
                if (!signedIn) {
                    Text("本功能使用网易云非公开客户端接口，可能因服务器接口变化、风控或验证要求失败。",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            if (!signedIn) {
                OutlinedButton(onClick = { serial++ }, enabled = !running) {
                    Text("重新生成")
                }
            }
        }
    )
}

private fun createQrCode(value: String): Bitmap {
    // This is always the known music.163.com login URL; no remote image is loaded.
    val matrix = QRCodeWriter().encode(
        value, BarcodeFormat.QR_CODE, 640, 640,
        mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8")
    )
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            pixels[y * matrix.width + x] = if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
