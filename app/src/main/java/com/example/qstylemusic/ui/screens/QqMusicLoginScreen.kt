package com.example.qstylemusic.ui.screens

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.qstylemusic.plugin.MplugLoginChallenge
import com.example.qstylemusic.plugin.MplugLoginClient
import com.example.qstylemusic.plugin.auth.QqMusicSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android-only QQ Music login challenge UI (inspired by QPlayer's login ABI).
 * The app fetches QR bytes and polls QQ; nothing runs on Windows/localhost.
 */
@Composable
fun QqMusicLoginScreen(onBack: () -> Unit, onSessionSaved: () -> Unit, autoStart: Boolean = false) {
    val context = LocalContext.current
    val client = remember(context) { MplugLoginClient(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var challenge by remember { mutableStateOf<MplugLoginChallenge?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("QQ 登录由 QQ 音乐插件提供。点击生成二维码后，在 QQ 中扫码确认。") }
    var qrPicture by remember { mutableStateOf<Uri?>(null) }
    var loggedIn by remember { mutableStateOf(QqMusicSession(context.applicationContext).hasLocalSession) }

    fun startChallenge() {
        if (busy) return
        scope.launch {
            busy = true
            status = "正在向 QQ 请求登录二维码…"
            try {
                // The installed QQ .mplug declares this method; Android owns all session secrets.
                val method = client.methods(MplugLoginClient.QQ_SOURCE_ID)
                    .firstOrNull { it.type == "qr" }
                    ?: error("QQ 音乐插件未声明二维码登录方式")
                qrPicture?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                qrPicture = null
                challenge = client.begin(MplugLoginClient.QQ_SOURCE_ID, method.id)
                status = "已生成二维码，等待手机 QQ 确认。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "创建二维码失败：${e.localizedMessage.orEmpty()}"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(autoStart) {
        if (autoStart && challenge == null && !loggedIn) startChallenge()
    }

    fun goBack() {
        val oldId = challenge?.id
        challenge = null
        if (oldId != null) scope.launch { client.cancel(MplugLoginClient.QQ_SOURCE_ID, oldId) }
        onBack()
    }
    BackHandler { goBack() }

    LaunchedEffect(challenge?.id) {
        val active = challenge ?: return@LaunchedEffect
        var failedChecks = 0
        while (isActive) {
            delay(1800L)
            val next = try {
                client.poll(MplugLoginClient.QQ_SOURCE_ID, active.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failedChecks++
                status = "QQ 登录状态检查失败：${e.localizedMessage.orEmpty()}" +
                    if (failedChecks < 4) "，将自动重试" else "，请重新生成二维码"
                if (failedChecks >= 4) challenge = null
                continue
            }
            failedChecks = 0
            status = next.message
            when (next.status) {
                MplugLoginChallenge.Status.WAITING,
                MplugLoginChallenge.Status.SCANNED -> Unit
                MplugLoginChallenge.Status.SUCCESS -> {
                    // Remove temporary QR from the public gallery after successful authorization.
                    qrPicture?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                    qrPicture = null
                    challenge = null
                    loggedIn = true
                    onSessionSaved()
                    return@LaunchedEffect
                }
                MplugLoginChallenge.Status.EXPIRED,
                MplugLoginChallenge.Status.FAILED -> {
                    challenge = null
                    return@LaunchedEffect
                }
            }
        }
    }

    val bitmap = remember(challenge?.qrImageBase64) {
        challenge?.qrImageBase64?.let { encoded ->
            runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { goBack() }) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text("QQ 音乐 · 扫码登录", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "完全在 Android 内完成：插件创建登录挑战 → QQ 扫码确认 → 插件轮询 → 本机加密保存会话。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            modifier = Modifier.fillMaxWidth(), enabled = !busy,
            onClick = { startChallenge() }
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(if (challenge == null) "生成 QQ 登录二维码" else "重新生成二维码")
        }
        if (bitmap != null) {
            Spacer(Modifier.height(2.dp))
            Image(
                bitmap = bitmap, contentDescription = "QQ 音乐账号登录二维码",
                modifier = Modifier.size(240.dp).align(Alignment.CenterHorizontally),
                contentScale = ContentScale.Fit
            )
            OutlinedButton(modifier = Modifier.align(Alignment.CenterHorizontally), onClick = {
                scope.launch {
                    try {
                        val encoded = challenge?.qrImageBase64 ?: error("二维码已失效")
                        val bytes = Base64.decode(encoded, Base64.DEFAULT)
                        qrPicture?.let { runCatching { context.contentResolver.delete(it, null, null) } }
                        qrPicture = withContext(Dispatchers.IO) {
                            saveQrToGallery(context, bytes)
                        }
                        status = "二维码已保存到相册。打开手机 QQ → 扫一扫 → 相册选择图片，确认后返回此页。"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        status = "保存二维码失败：${e.localizedMessage.orEmpty()}"
                    }
                }
            }) { Text("保存二维码到相册（单手机登录）") }
            Text(
                "只有这一部手机：保存二维码后在 QQ 扫一扫里选择相册图片。QQ 是否允许从本机相册识别该二维码，取决于 QQ 当前版本。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            status,
            color = if (status.contains("失败") || status.contains("失效"))
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        if (challenge != null) {
            OutlinedButton(onClick = {
                val id = challenge?.id
                challenge = null
                status = "已取消登录挑战"
                if (id != null) scope.launch { client.cancel(MplugLoginClient.QQ_SOURCE_ID, id) }
            }) { Text("取消当前二维码") }
        }
        if (loggedIn) {
            OutlinedButton(onClick = {
                client.logout(MplugLoginClient.QQ_SOURCE_ID)
                loggedIn = false
                status = "已清除 QQ 音乐登录凭证"
            }) { Text("退出 QQ 音乐账号") }
        }
        Text(
            "说明：此功能基于第三方非官方协议，不保证 QQ 随时允许扫码或第三方播放；VIP、版权与地区限制仍由 QQ 音乐决定。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Android 10+ writes app-owned pictures without needing media storage permissions. */
private fun saveQrToGallery(context: android.content.Context, bytes: ByteArray): Uri {
    require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "Android 9 或更早版本请使用系统截图或另一台手机扫码"
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: error("不是有效二维码图片")
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "QStyleMusic_QQ_Login_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QStyleMusic")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("无法在相册创建二维码图片")
    try {
        resolver.openOutputStream(uri)?.use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "二维码保存失败" }
        } ?: error("无法写入二维码图片")
        resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null)
        return uri
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        throw e
    } finally {
        bitmap.recycle()
    }
}
