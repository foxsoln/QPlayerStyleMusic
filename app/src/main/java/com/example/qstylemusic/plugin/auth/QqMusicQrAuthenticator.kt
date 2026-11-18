package com.example.qstylemusic.plugin.auth

import android.content.Context
import android.util.Base64
import com.example.qstylemusic.plugin.MplugLoginChallenge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * QQ QR/OAuth login runs in the Android app, talking only to QQ/Tencent HTTPS hosts.
 * No local HTTP server, Node process, PC browser, ADB tunnel, or user-supplied API host.
 * The protocol is unofficial and can stop working if QQ changes its login endpoint.
 */
internal class QqMusicQrAuthenticator(context: Context) {
    private val session = QqMusicSession(context.applicationContext)
    private val mutex = Mutex()
    private var attempt: Attempt? = null

    private data class Attempt(
        val id: String,
        val createdAt: Long,
        val qrSignature: String,
        val loginSignature: String,
        val cookies: MutableMap<String, String>
    )

    private data class Response(
        val status: Int,
        val headers: Map<String, List<String>>,
        val bytes: ByteArray
    ) {
        fun header(name: String): String? = headers.entries.firstOrNull {
            it.key.equals(name, true)
        }?.value?.firstOrNull()

        fun setCookies(): List<String> = headers.entries.filter {
            it.key.equals("Set-Cookie", true)
        }.flatMap { it.value }

        fun bodyText(): String = String(bytes, Charsets.UTF_8)
    }

    suspend fun begin(): MplugLoginChallenge = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Invalidate any earlier challenge before creating a new one.
            attempt = null
            val jar = linkedMapOf<String, String>()
            val initial = request("GET", XLOGIN_URL, emptyMap(), referer = "https://y.qq.com/", maxBytes = 0)
            jar.putAll(QqMusicProtocol.cookiePairs(initial.setCookies()))
            val query = queryOf(
                "appid" to APP_ID, "e" to "2", "l" to "M", "s" to "3", "d" to "72",
                "v" to "4", "t" to (System.currentTimeMillis() % 100000).toString(),
                "daid" to DAID, "pt_3rd_aid" to CLIENT_ID, "u1" to LOGIN_JUMP
            )
            val qrResponse = request("GET", "https://ssl.ptlogin2.qq.com/ptqrshow?$query",
                jar, referer = XLOGIN_URL, maxBytes = 1024 * 1024)
            require(qrResponse.status == 200) { "QQ 二维码请求 HTTP ${qrResponse.status}" }
            jar.putAll(QqMusicProtocol.cookiePairs(qrResponse.setCookies()))
            val signature = jar["qrsig"].orEmpty()
            require(signature.isNotBlank()) { "QQ 二维码没有返回 qrsig" }
            val bytes = qrResponse.bytes
            require(bytes.size > 8 && (
                bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) ||
                (bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte())
            )) { "QQ 返回的不是有效二维码图片" }
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            attempt = Attempt(id, now, signature, jar["pt_login_sig"].orEmpty(), jar)
            MplugLoginChallenge(
                id = id, status = MplugLoginChallenge.Status.WAITING,
                qrImageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                message = "二维码已生成。请用手机 QQ 扫一扫确认登录。",
                expiresAtMs = now + 145_000L
            )
        }
    }

    suspend fun poll(challengeId: String): MplugLoginChallenge = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = attempt
                ?: return@withLock MplugLoginChallenge(challengeId, MplugLoginChallenge.Status.EXPIRED,
                    message = "登录挑战已结束，请重新生成二维码")
            if (current.id != challengeId) {
                return@withLock MplugLoginChallenge(challengeId, MplugLoginChallenge.Status.EXPIRED,
                    message = "不是当前二维码，请重新生成")
            }
            if (System.currentTimeMillis() - current.createdAt > 145_000L) {
                attempt = null
                return@withLock MplugLoginChallenge(challengeId, MplugLoginChallenge.Status.EXPIRED,
                    message = "二维码已过期，请刷新")
            }
            val params = queryOf(
                "u1" to LOGIN_JUMP,
                "ptqrtoken" to QqMusicProtocol.hash33(current.qrSignature).toString(),
                "ptredirect" to "0", "h" to "1", "t" to "1", "g" to "1",
                "from_ui" to "1", "ptlang" to "2052",
                "action" to "0-0-${System.currentTimeMillis()}",
                "js_ver" to "23111510", "js_type" to "1",
                "login_sig" to current.loginSignature,
                "pt_uistyle" to "40", "aid" to APP_ID, "daid" to DAID,
                "pt_3rd_aid" to CLIENT_ID
            )
            val response = request(
                "GET", "https://ssl.ptlogin2.qq.com/ptqrlogin?$params", current.cookies,
                referer = XLOGIN_URL, maxBytes = 16_384
            )
            require(response.status == 200) { "QQ 扫码状态 HTTP ${response.status}" }
            val callback = QqMusicProtocol.ptuiCallback(response.bodyText())
            when (callback.status) {
                66 -> MplugLoginChallenge(challengeId, MplugLoginChallenge.Status.WAITING,
                    message = "等待 QQ 扫码")
                67 -> MplugLoginChallenge(challengeId, MplugLoginChallenge.Status.SCANNED,
                    message = "已扫码，请在 QQ 中确认授权")
                65 -> {
                    attempt = null
                    MplugLoginChallenge(challengeId, MplugLoginChallenge.Status.EXPIRED,
                        message = "二维码已失效，请刷新")
                }
                0 -> {
                    // QQ may set uin/skey on the successful poll response.
                    // Preserve these before the check_sig/OAuth exchange.
                    current.cookies.putAll(QqMusicProtocol.cookiePairs(response.setCookies()))
                    // Only a fully exchanged QQ Music credential counts as login success.
                    try {
                        val credentials = exchange(current, callback.redirectUrl.orEmpty())
                        attempt = null
                        MplugLoginChallenge(challengeId, MplugLoginChallenge.Status.SUCCESS,
                            message = "QQ 音乐授权完成：${credentials.uin}")
                    } catch (error: Exception) {
                        attempt = null
                        MplugLoginChallenge(challengeId, MplugLoginChallenge.Status.FAILED,
                            message = "扫码确认成功，但 QQ 音乐凭证交换失败：${error.localizedMessage.orEmpty()}")
                    }
                }
                68 -> {
                    attempt = null
                    MplugLoginChallenge(challengeId, MplugLoginChallenge.Status.FAILED,
                        message = "用户取消了登录")
                }
                else -> {
                    attempt = null
                    MplugLoginChallenge(challengeId, MplugLoginChallenge.Status.FAILED,
                        message = "QQ 登录返回状态 ${callback.status}")
                }
            }
        }
    }

    suspend fun cancel(challengeId: String) = mutex.withLock {
        if (attempt?.id == challengeId) attempt = null
    }

    private fun exchange(current: Attempt, callbackUrl: String): QqMusicSession.Credentials {
        val checkSigUrl = QqMusicProtocol.checkedSigUrl(callbackUrl)
        val checkResponse = request("GET", checkSigUrl, current.cookies,
            referer = "https://graph.qq.com/", maxBytes = 0)
        require(checkResponse.status in 200..399) {
            "QQ check_sig 返回 HTTP ${checkResponse.status}"
        }
        current.cookies.putAll(QqMusicProtocol.cookiePairs(checkResponse.setCookies()))
        val pSkey = current.cookies["p_skey"].orEmpty()
        require(pSkey.isNotBlank()) { "QQ check_sig 未产生 p_skey，无法继续授权" }
        val gtk = QqMusicProtocol.gtk(pSkey)

        val form = queryOf(
            "response_type" to "code", "client_id" to CLIENT_ID,
            "redirect_uri" to "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/",
            "scope" to "get_user_info,get_app_friends", "state" to "state", "switch" to "",
            "from_ptlogin" to "1", "src" to "1", "update_auth" to "1",
            "openapi" to "1010_1030", "g_tk" to gtk.toString(),
            "auth_time" to System.currentTimeMillis().toString(),
            "ui" to UUID.randomUUID().toString()
        )
        val authCookies = current.cookies.filterKeys { it !in setOf("qrsig", "pt_login_sig") }
        val authorize = request("POST", "https://graph.qq.com/oauth2.0/authorize", authCookies,
            referer = "https://graph.qq.com/oauth2.0/show", formBody = form,
            contentType = "application/x-www-form-urlencoded; charset=UTF-8", maxBytes = 64 * 1024)
        require(authorize.status in 300..399) {
            "QQ OAuth 没有发放授权跳转（HTTP ${authorize.status}）"
        }
        val code = QqMusicProtocol.oauthCode(authorize.header("Location"))
        val requestBody = JSONObject()
            .put("comm", JSONObject()
                .put("g_tk", gtk).put("platform", "yqq")
                .put("ct", 24).put("cv", 0))
            .put("req", JSONObject()
                .put("module", "QQConnectLogin.LoginServer")
                .put("method", "QQLogin")
                .put("param", JSONObject().put("code", code)))
        // Exchange only with QQ Music's own endpoint. Do not send OAuth code to a proxy.
        val musicResult = request("POST", "https://u.y.qq.com/cgi-bin/musicu.fcg",
            authCookies, referer = "https://y.qq.com/", formBody = requestBody.toString(),
            contentType = "application/json; charset=UTF-8", maxBytes = 512 * 1024)
        require(musicResult.status == 200) {
            "QQ 音乐凭证接口 HTTP ${musicResult.status}"
        }
        val response = JSONObject(musicResult.bodyText())
        val item = response.optJSONObject("req")
            ?: response.optJSONObject("QQConnectLogin.LoginServer.QQLogin")
            ?: error("QQ 音乐凭证接口响应结构已变更")
        require(item.optInt("code", -1) == 0) { "QQ 音乐登录 code=${item.optInt("code", -1)}" }
        val data = item.optJSONObject("data") ?: error("QQ 音乐登录结果缺少数据")
        val musicId = data.optString("musicid", data.optString("str_musicid"))
            .ifBlank { current.cookies["uin"]?.removePrefix("o").orEmpty() }
        val musicKey = data.optString("musickey")
            .ifBlank { QqMusicProtocol.cookiePairs(musicResult.setCookies())["qm_keyst"].orEmpty() }
        require(musicId.matches(Regex("[0-9]{4,20}")) && musicKey.length >= 12) {
            "QQ 音乐没有返回完整 musicid/musickey，不能将网页 QQ 登录视为播放授权"
        }
        val acceptedCookies = current.cookies + QqMusicProtocol.cookiePairs(musicResult.setCookies())
        return session.saveFromQrExchange(musicId, musicKey, acceptedCookies)
    }

    /** Fixed HTTPS endpoints; no redirects and no cookies sent to arbitrary hosts. */
    private fun request(
        method: String,
        address: String,
        cookies: Map<String, String>,
        referer: String,
        formBody: String? = null,
        contentType: String? = null,
        maxBytes: Int
    ): Response {
        val uri = URI(address)
        require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
            uri.host?.lowercase() in setOf(
                "xui.ptlogin2.qq.com", "ssl.ptlogin2.qq.com", "ptlogin2.qq.com",
                "ptlogin2.graph.qq.com", "ssl.ptlogin2.graph.qq.com",
                "graph.qq.com", "u.y.qq.com"
            )) { "拒绝向非 QQ 官方认证域名发送请求" }
        val conn = URL(address).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = 6_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("User-Agent", ANDROID_AGENT)
            conn.setRequestProperty("Referer", referer)
            if (cookies.isNotEmpty()) conn.setRequestProperty(
                "Cookie", cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
            )
            if (formBody != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType ?: "application/json")
                conn.outputStream.use { stream ->
                    stream.write(formBody.toByteArray(Charsets.UTF_8))
                }
            }
            val status = conn.responseCode
            val headers = conn.headerFields.entries.mapNotNull { entry ->
                val key = entry.key ?: return@mapNotNull null
                key to (entry.value?.filterNotNull().orEmpty())
            }.toMap()
            val bytes = if (maxBytes == 0) ByteArray(0) else {
                val stream = if (status in 200..399) conn.inputStream else conn.errorStream
                if (stream == null) ByteArray(0) else stream.use { input ->
                    val result = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(result.size() + count <= maxBytes) { "QQ 授权响应超过大小限制" }
                        result.write(buffer, 0, count)
                    }
                    result.toByteArray()
                }
            }
            return Response(status, headers, bytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun queryOf(vararg fields: Pair<String, String>): String = fields.joinToString("&") {
        "${URLEncoder.encode(it.first, "UTF-8")}=${URLEncoder.encode(it.second, "UTF-8")}"
    }

    private companion object {
        const val APP_ID = "716027609"
        const val DAID = "383"
        const val CLIENT_ID = "100497308"
        const val LOGIN_JUMP = "https://graph.qq.com/oauth2.0/login_jump"
        const val ANDROID_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        const val XLOGIN_URL = "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=716027609&style=40&s_url=https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump&daid=383&pt_3rd_aid=100497308"
    }
}
