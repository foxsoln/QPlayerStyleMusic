package com.example.qstylemusic.plugin

import com.example.qstylemusic.plugin.auth.QqMusicSession
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Minimal restricted HTTP gateway for user-specified API servers.
 * - No arbitrary fetch(url) binding is exposed to the plugin.
 * - JS supplies only a relative API path and small scalar query parameters.
 * - Only HTTPS, or development-only HTTP loopback (adb reverse / emulator), is allowed.
 * - No cookies, Authorization headers, redirects, file access, or cross-origin requests.
 * - Response is bounded to 1 MiB. This is NOT a sandbox for untrusted plugins.
 */
class MplugHttpClient {
    suspend fun request(
        baseUrl: String,
        plan: JSONObject,
        nativeQqCredentials: QqMusicSession.Credentials? = null
    ): JSONObject = withTimeout(12_000L) {
        withContext(Dispatchers.IO) {
            val origin = URI(baseUrl.trim().removeSuffix("/"))
            val host = origin.host?.lowercase().orEmpty()
            val allowedLocal = host in setOf("localhost", "127.0.0.1", "10.0.2.2")
            require(host.isNotEmpty() && origin.userInfo == null && origin.fragment == null && origin.query == null) {
                "服务地址无效；请填写 http://127.0.0.1:3000 或 HTTPS 地址"
            }
            require(origin.scheme == "https" || (origin.scheme == "http" && allowedLocal)) {
                "公网服务必须使用 HTTPS；HTTP 仅允许本机调试"
            }
            require(origin.rawPath.isNullOrEmpty() || origin.rawPath == "/") {
                "服务地址只填写域名和端口，不要包含路径"
            }
            require(origin.port == -1 || origin.port in 1..65535) { "服务端口不正确" }
            val path = plan.getString("path")
            require(Regex("/[A-Za-z0-9/_-]{1,80}").matches(path) && !path.contains("//") && !path.contains("..")) {
                "插件请求了不允许的 API 路径"
            }
            val query = plan.optJSONObject("query") ?: JSONObject()
            require(query.length() <= 12) { "参数数量过多" }
            val keys = query.keys().asSequence().toList().sorted()
            val encodedQuery = keys.joinToString("&") { key ->
                require(Regex("[A-Za-z][A-Za-z0-9_]{0,35}").matches(key)) { "非法参数名" }
                val value = query.get(key)
                require(value is String || value is Number || value is Boolean) { "网络参数只能是字符串或数字" }
                val text = value.toString()
                val maxLength = if (nativeQqCredentials != null &&
                    host in setOf("u.y.qq.com", "c.y.qq.com")) 2000 else 500
                require(text.length <= maxLength) { "请求参数过长" }
                "${urlEncode(key)}=${urlEncode(text)}"
            }
            val base = origin.toString().trimEnd('/')
            val url = URI(base + path + if (encodedQuery.isEmpty()) "" else "?$encodedQuery").toURL()
            val connection = (url.openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 5_000
                connection.readTimeout = 7_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                // Only our native host can attach the QQ session; a .mplug has no way to
                // set arbitrary cookies or change this destination.
                if (host in setOf("u.y.qq.com", "c.y.qq.com") && nativeQqCredentials != null) {
                    connection.setRequestProperty("Cookie", nativeQqCredentials.cookieHeader())
                }
                connection.setRequestProperty("User-Agent", "QStyleMusic-Mplug/0.9 (Android)")
                // Public NetEase web endpoints may require the normal website Referer.
                // Do NOT forward credentials or cookies from the older QR-login module.
                if (host == "music.163.com") {
                    connection.setRequestProperty("Referer", "https://music.163.com/")
                } else if (host == "u.y.qq.com") {
                    connection.setRequestProperty("Referer", "https://y.qq.com/")
                } else if (host == "c.y.qq.com") {
                    connection.setRequestProperty("Referer", "https://y.qq.com/portal/player.html")
                }
                val status = connection.responseCode
                require(status in 200..299) {
                    if (host == "music.163.com")
                        "网易云直连接口返回 HTTP $status；可能是接口调整、访问限制或需要登录授权"
                    else if (host == "u.y.qq.com" || host == "c.y.qq.com")
                        "QQ 音乐接口返回 HTTP $status；可能是接口调整或请求受到访问限制"
                    else
                        "音源 API 返回 HTTP $status（可能是服务未启动或接口不可用）"
                }
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val block = ByteArray(8192)
                    while (true) {
                        val count = input.read(block)
                        if (count < 0) break
                        require(output.size() + count <= 1024 * 1024) { "音源 API 返回内容超过 1 MiB" }
                        output.write(block, 0, count)
                    }
                }
                JSONObject(output.toString(Charsets.UTF_8.name()))
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
