package com.example.qstylemusic.plugin

import android.content.Context
import com.example.qstylemusic.plugin.auth.QqMusicQrAuthenticator
import com.example.qstylemusic.plugin.auth.QqMusicSession

/** QPlayer-inspired plugin login ABI: methods -> begin -> poll -> success/logout. */
data class MplugLoginMethod(val id: String, val type: String, val label: String)

data class MplugLoginChallenge(
    val id: String,
    val status: Status,
    val message: String = "",
    val qrImageBase64: String? = null,
    val expiresAtMs: Long = 0L
) {
    enum class Status { WAITING, SCANNED, SUCCESS, EXPIRED, FAILED }
}

/**
 * .mplug manifest owns the login capability; the Android host owns authentication
 * transports and encrypted session persistence. This retains the existing QuickJS
 * network sandbox, so no arbitrary JS source can read QQ cookies or OAuth codes.
 */
class MplugLoginClient(context: Context) {
    private val appContext = context.applicationContext
    private val store = MplugStore(appContext)
    private val qqLogin = QqMusicQrAuthenticator(appContext)

    private fun requireQqPlugin() {
        val archive = store.loadEnabled(QQ_SOURCE_ID)
        require(archive.manifest.apiVersion == 2 &&
            "login" in archive.manifest.capabilities &&
            "auth.qr" in archive.manifest.permissions) {
            "请在音源插件管理中安装并启用最新 QQ 音乐 .mplug（需 login/auth.qr 能力）"
        }
    }

    fun methods(pluginId: String): List<MplugLoginMethod> {
        require(pluginId == QQ_SOURCE_ID) { "此版本尚未支持该插件的登录方式" }
        requireQqPlugin()
        return listOf(MplugLoginMethod("qq.qr", "qr", "使用手机 QQ 扫码"))
    }

    suspend fun begin(pluginId: String, methodId: String): MplugLoginChallenge {
        require(methods(pluginId).any { it.id == methodId }) { "插件登录方式无效" }
        return qqLogin.begin()
    }

    suspend fun poll(pluginId: String, challengeId: String): MplugLoginChallenge {
        require(pluginId == QQ_SOURCE_ID)
        requireQqPlugin()
        return qqLogin.poll(challengeId)
    }

    suspend fun cancel(pluginId: String, challengeId: String) {
        if (pluginId == QQ_SOURCE_ID) qqLogin.cancel(challengeId)
    }

    fun logout(pluginId: String) {
        require(pluginId == QQ_SOURCE_ID)
        QqMusicSession(appContext).clear()
    }

    companion object { const val QQ_SOURCE_ID = "qqmusic.direct" }
}
