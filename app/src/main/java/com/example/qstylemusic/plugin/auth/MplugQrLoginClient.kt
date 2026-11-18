package com.example.qstylemusic.plugin.auth

import android.content.Context
import com.example.qstylemusic.plugin.MplugJsRuntime
import com.example.qstylemusic.plugin.MplugStore
import org.json.JSONObject

/**
 * The .mplug plugin declares QR-login request plans and parses status codes.
 * Only the native host talks to the HTTPS service and saves its cookies.
 * This first transport supports NetEase QR login; additional providers can
 * implement their own isolated transports without exposing Android objects to JS.
 */
class MplugQrLoginClient(context: Context) {
    private val app = context.applicationContext
    private val store = MplugStore(app)
    private val js = MplugJsRuntime()
    private val transport = NeteaseQrTransport(app)

    val loggedIn get() = transport.loggedIn
    fun logoutLocal() = transport.logoutLocal()

    data class Challenge(val key: String, val url: String)
    enum class Status { WAITING, SCANNED, SUCCESS, EXPIRED }

    suspend fun begin(pluginId: String): Challenge {
        val archive = enabledAuthPlugin(pluginId)
        val params = JSONObject()
        val plan = JSONObject(js.call(archive, "buildRequest", JSONObject()
            .put("action", "loginQrBegin").put("params", params)))
        require(plan.getString("path") == NeteaseQrTransport.QR_NEW_KEY) { "插件认证请求路径不符合要求" }
        val body = transport.execute(plan.getString("path"), plan.getJSONObject("query"))
        val parsed = JSONObject(js.call(archive, "parseResponse", JSONObject()
            .put("action", "loginQrBegin").put("params", params).put("response", body)))
        val key = parsed.getString("key")
        require(key.matches(Regex("[A-Za-z0-9_-]{8,256}"))) { "网易云返回的二维码 key 无效" }
        val expected = "https://music.163.com/login?codekey=$key"
        require(parsed.getString("url") == expected) { "插件返回了非网易云官方扫码链接" }
        return Challenge(key, expected)
    }

    suspend fun poll(pluginId: String, key: String): Status {
        val archive = enabledAuthPlugin(pluginId)
        require(key.matches(Regex("[A-Za-z0-9_-]{8,256}")))
        val params = JSONObject().put("key", key)
        val plan = JSONObject(js.call(archive, "buildRequest", JSONObject()
            .put("action", "loginQrPoll").put("params", params)))
        require(plan.getString("path") == NeteaseQrTransport.QR_POLL) { "插件认证请求路径不符合要求" }
        val body = transport.execute(plan.getString("path"), plan.getJSONObject("query"))
        val status = when (body.optInt("code", -1)) {
            800 -> Status.EXPIRED
            801 -> Status.WAITING
            802 -> Status.SCANNED
            803 -> {
                require(transport.loggedIn) {
                    "扫码已确认，但未收到可保存的会话 Cookie；当前接口可能已发生变化"
                }
                Status.SUCCESS
            }
            else -> {
                // Keep the actual provider error code visible (but never log cookies).
                val code = body.optInt("code", -1)
                val reason = body.optString("message").ifBlank { body.optString("msg") }
                    .take(120)
                val detail = if (reason.isBlank()) "" else "：$reason"
                error("网易云登录接口 code=$code$detail。若需安全验证，请在网易云官方 App 内完成。")
            }
        }
        // Evaluate plugin's declared parser for protocol consistency, but trust
        // the native code status, not an arbitrary plugin-supplied success value.
        val response = JSONObject(js.call(archive, "parseResponse", JSONObject()
            .put("action", "loginQrPoll").put("params", params).put("response", body)))
        require(response.optString("status") == status.name.lowercase()) { "插件与宿主的扫码状态不一致" }
        return status
    }

    private fun enabledAuthPlugin(pluginId: String) = store.loadEnabled(pluginId).also {
        require(it.manifest.id == "netease.source") { "当前版本仅支持网易云扫码认证" }
        require(it.manifest.apiVersion == 2 && "loginQr" in it.manifest.capabilities &&
            "auth.qr" in it.manifest.permissions) {
            "请安装支持 loginQr 的新版网易云 .mplug"
        }
    }
}
