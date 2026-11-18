package com.example.qstylemusic.plugin.auth

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * QPlayer's PC EAPI client identity (main.js OS_MAP.pc).
 * Do not mix an iPhone os/appver with a Windows osver. The EAPI User-Agent
 * intentionally stays the same as the reference client implementation.
 */
private object PcQrIdentity {
    const val OS = "pc"
    const val OSVER = "Microsoft-Windows-10-Professional-build-19045-64bit"
    const val APPVER = "3.1.17.204416"
    const val CHANNEL = "netease"
    const val EAPI_USER_AGENT = "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)"
}

/**
 * Direct NetEase QR-login transport, corresponding to the EAPI paths used by QPlayer.
 * No third-party API gateway is involved. This uses a non-public client protocol and
 * is not guaranteed to remain compatible with NetEase services.
 *
 * SECURITY:
 * - Only the TWO QR-login endpoints are accessible through this transport.
 * - The destination is the fixed HTTPS endpoint interfacepc.music.163.com.
 * - Cookies never enter the QuickJS runtime or any configurable API gateway.
 * - Login cookies are encrypted using an Android Keystore AES-GCM key.
 * - The JS plugin receives only sanitized status JSON (no cookie fields).
 */
class NeteaseQrTransport(context: Context) {
    private val credentials = NeteaseQrCredentials(context.applicationContext)

    val loggedIn: Boolean get() = credentials.hasSession()

    fun logoutLocal() = credentials.clear()

    suspend fun execute(path: String, request: JSONObject): JSONObject = withTimeout(16_000L) {
        withContext(Dispatchers.IO) {
            require(path == QR_NEW_KEY || path == QR_POLL) { "非扫码认证接口不允许访问" }
            require(request.optInt("type", -1) == 3) { "扫码类型必须为 3" }
            val body = if (path == QR_NEW_KEY) {
                require(request.length() == 1) { "生成二维码参数不合法" }
                JSONObject().put("type", 3)
            } else {
                val key = request.optString("key")
                require(key.matches(Regex("[A-Za-z0-9_-]{8,256}"))) { "二维码 key 不合法" }
                require(request.length() == 2) { "查询扫码状态参数不合法" }
                JSONObject().put("key", key).put("type", 3)
            }
            val header = credentials.makeClientHeader()
            body.put("e_r", false)
            body.put("header", header)
            val json = body.toString()
            val digest = md5("nobody${path}use${json}md5forencrypt")
            val message = "$path-36cd479b6b5-$json-36cd479b6b5-$digest"
            val encrypted = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_KEY.toByteArray(Charsets.UTF_8), "AES"))
                doFinal(message.toByteArray(Charsets.UTF_8))
            }
            val formBody = "params=" + URLEncoder.encode(hex(encrypted), "UTF-8")
            val url = URL("https://interfacepc.music.163.com/eapi/${path.removePrefix("/api/")}")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 6_000
                conn.readTimeout = 8_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("Accept", "application/json")
                // The upstream QPlayer plugin keeps this EAPI User-Agent even
                // when the signed EAPI header declares the PC client profile.
                conn.setRequestProperty("User-Agent", PcQrIdentity.EAPI_USER_AGENT)
                conn.setRequestProperty("Cookie", credentials.cookieHeader(header))
                conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                require(code in 200..299) { "网易云扫码接口 HTTP $code" }
                val output = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(output.size() + n <= 512 * 1024) { "扫码响应过大" }
                        output.write(buffer, 0, n)
                    }
                }
                val result = JSONObject(output.toString("UTF-8"))
                // First consume session secrets on the Kotlin side. Then remove them
                // before the response is delivered to an untrusted JavaScript plugin.
                val setCookies = conn.headerFields.orEmpty()
                    .filterKeys { it?.equals("Set-Cookie", ignoreCase = true) == true }
                    .values.flatMap { it.orEmpty() }
                credentials.absorb(setCookies, result.optString("cookie"))
                result.remove("cookie")
                result.remove("cookies")
                result.remove("token")
                result
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Read-only NetEase account requests using the SAME encrypted login session.
     * Unlike a generic HTTP bridge, neither the plugin nor the UI can provide
     * an arbitrary URL/host/path/header, or access plaintext login cookies.
     * QR login's existing execute() is intentionally left unchanged.
     */
    suspend fun executeAccount(path: String, params: JSONObject): JSONObject = withTimeout(25_000L) {
        withContext(Dispatchers.IO) {
            require(credentials.hasSession()) { "本机尚未保存网易云登录态，请先完成 PC 模式扫码" }
            val safeParams = when (path) {
                "/api/nuser/account/get" -> {
                    require(params.length() == 0) { "账号资料接口参数不合法" }
                    JSONObject()
                }
                "/api/user/playlist" -> {
                    val uid = params.optString("uid")
                    require(uid.matches(Regex("[0-9]{1,19}"))) { "用户 ID 不合法" }
                    val limit = params.optInt("limit", 60)
                    val offset = params.optInt("offset", 0)
                    require(limit in 1..100 && offset in 0..10000) { "歌单分页参数不合法" }
                    JSONObject().put("uid", uid.toLong()).put("limit", limit)
                        .put("offset", offset).put("includeVideo", true)
                }
                "/api/v6/playlist/detail" -> {
                    val pid = params.optString("id")
                    require(pid.matches(Regex("[0-9]{1,19}"))) { "歌单 ID 不合法" }
                    JSONObject().put("id", pid.toLong()).put("n", 500).put("s", 8)
                }
                // Controlled NetEase discovery and search. No arbitrary URL is accepted.
                "/api/cloudsearch/pc" -> {
                    val keyword = params.optString("s").trim()
                    require(keyword.length in 1..100) { "搜索关键词长度须在 1～100 字符之间" }
                    val limit = params.optInt("limit", 30)
                    val offset = params.optInt("offset", 0)
                    require(limit in 1..50 && offset in 0..500) { "搜索分页参数不合法" }
                    JSONObject().put("s", keyword).put("type", 1)
                        .put("limit", limit).put("offset", offset).put("total", "true")
                }
                "/api/v3/discovery/recommend/songs" -> {
                    require(params.length() == 0) { "每日推荐不允许自定义请求参数" }
                    JSONObject().put("afresh", false)
                }
                "/api/personalized/playlist" -> {
                    val limit = params.optInt("limit", 10)
                    require(limit in 1..30) { "推荐歌单数量不合法" }
                    JSONObject().put("limit", limit)
                }
                // Only allow playing/lyric lookup for a numeric NetEase song ID.
                // The login cookie remains inside the native encrypted credential jar.
                "/api/song/enhance/player/url/v1" -> {
                    val sid = params.optString("id")
                    require(sid.matches(Regex("[0-9]{1,19}"))) { "歌曲 ID 不合法" }
                    // Try the highest normal stereo tier first. The service may
                    // downgrade according to this account's entitlement.
                    val level = params.optString("level", "hires")
                    require(level in setOf("jymaster", "hires", "lossless",
                        "exhigh", "higher", "standard")) { "不支持的音质等级" }
                    JSONObject()
                        .put("ids", "[$sid]")
                        .put("level", level)
                        .put("encodeType", "flac")
                }
                "/api/song/enhance/player/url" -> {
                    val sid = params.optString("id")
                    require(sid.matches(Regex("[0-9]{1,19}"))) { "歌曲 ID 不合法" }
                    JSONObject()
                        .put("ids", "[$sid]")
                        .put("br", 999000)
                }
                "/api/song/lyric/v1" -> {
                    val sid = params.optString("id")
                    require(sid.matches(Regex("[0-9]{1,19}"))) { "歌曲 ID 不合法" }
                    JSONObject()
                        .put("id", sid.toLong())
                        .put("cp", false)
                        .put("lv", 0).put("tv", 0).put("rv", 0)
                        .put("kv", 0).put("yv", 0).put("ytv", 0).put("yrv", 0)
                }
                "/api/v3/song/detail" -> {
                    val ids = params.optJSONArray("ids") ?: error("缺少歌曲 ID 列表")
                    require(ids.length() in 1..50) { "一次最多查询 50 首歌曲" }
                    val rows = org.json.JSONArray()
                    for (i in 0 until ids.length()) {
                        val id = ids.optString(i)
                        require(id.matches(Regex("[0-9]{1,19}"))) { "歌曲 ID 不合法" }
                        rows.put(JSONObject().put("id", id.toLong()))
                    }
                    JSONObject().put("c", rows.toString()).put("rv", true)
                }
                else -> error("账号接口不在允许列表中")
            }
            // PC EAPI identity must be consistent for all account calls.
            val header = credentials.makeClientHeader()
            safeParams.put("e_r", false).put("header", header)
            val json = safeParams.toString()
            val digest = md5("nobody${path}use${json}md5forencrypt")
            val message = "$path-36cd479b6b5-$json-36cd479b6b5-$digest"
            val encrypted = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(EAPI_KEY.toByteArray(Charsets.UTF_8), "AES"))
                doFinal(message.toByteArray(Charsets.UTF_8))
            }
            val form = "params=" + URLEncoder.encode(hex(encrypted), "UTF-8")
            val url = URL("https://interfacepc.music.163.com/eapi/${path.removePrefix("/api/")}")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 7_000
                conn.readTimeout = 12_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", PcQrIdentity.EAPI_USER_AGENT)
                conn.setRequestProperty("Cookie", credentials.cookieHeader(header))
                conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                val httpCode = conn.responseCode
                require(httpCode in 200..299) { "网易云账号接口 HTTP $httpCode" }
                val output = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(output.size() + n <= 4 * 1024 * 1024) { "网易云歌单响应过大" }
                        output.write(buffer, 0, n)
                    }
                }
                val result = JSONObject(output.toString("UTF-8"))
                val setCookies = conn.headerFields.orEmpty()
                    .filterKeys { it?.equals("Set-Cookie", ignoreCase = true) == true }
                    .values.flatMap { it.orEmpty() }
                credentials.absorb(setCookies, result.optString("cookie"))
                result.remove("cookie")
                result.remove("cookies")
                result.remove("token")
                val code = result.optInt("code", -1)
                if (code != 200) {
                    val reason = result.optString("message").ifBlank { result.optString("msg") }
                        .take(100)
                    error("网易云账号接口 code=$code${if (reason.isBlank()) "" else "：$reason"}；如登录已失效请重新扫码")
                }
                result
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun md5(value: String) = hex(MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))).lowercase()

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it.toInt() and 0xff) }

    companion object {
        const val QR_NEW_KEY = "/api/login/qrcode/unikey"
        const val QR_POLL = "/api/login/qrcode/client/login"
        private const val EAPI_KEY = "e82ckenh8dichen8"
    }
}

/** A per-app encrypted cookie jar. Encryption keys are kept in Android Keystore. */
private class NeteaseQrCredentials(context: Context) {
    private val prefs = context.getSharedPreferences("netease_qr_credentials_v1", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val allowedCookies = setOf(
        "MUSIC_U", "MUSIC_R_T", "MUSIC_A_T", "MUSIC_A", "__csrf",
        "NMTID", "MUSIC_SNS", "__remember_me", "_ntes_nuid"
    )

    fun hasSession(): Boolean = getCookies().optString("MUSIC_U").isNotBlank()

    fun clear() { prefs.edit().remove("session").apply() }

    private fun deviceId(): String {
        prefs.getString("device_id", null)?.takeIf { it.matches(Regex("[0-9A-F]{52}")) }?.let { return it }
        val bytes = ByteArray(26).also(random::nextBytes)
        val id = bytes.joinToString("") { "%02X".format(it.toInt() and 0xff) }
        prefs.edit().putString("device_id", id).apply()
        return id
    }

    fun makeClientHeader(): JSONObject {
        val cookies = getCookies()
        val now = System.currentTimeMillis()
        return JSONObject()
            .put("osver", PcQrIdentity.OSVER)
            .put("deviceId", deviceId())
            .put("os", PcQrIdentity.OS)
            .put("appver", PcQrIdentity.APPVER)
            .put("versioncode", "140")
            .put("mobilename", "")
            .put("buildver", (now / 1000L).toString())
            .put("resolution", "1920x1080")
            .put("__csrf", cookies.optString("__csrf"))
            .put("channel", PcQrIdentity.CHANNEL)
            .put("requestId", "${now}_${random.nextInt(1000).toString().padStart(4, '0')}")
            .apply {
                // Session fields stay in the native encrypted cookie jar.
                for (name in listOf("MUSIC_U", "MUSIC_A", "NMTID")) {
                    cookies.optString(name).takeIf { it.isNotBlank() }?.let { put(name, it) }
                }
            }
    }

    fun cookieHeader(header: JSONObject): String {
        // QPlayer's EAPI headerCookie() mirrors only the signed EAPI header.
        // Avoid mixing the PC identity with unrelated/stale stored cookies.
        val names = header.keys().asSequence().toList().sorted()
        return names.joinToString("; ") { name ->
            "${encodeCookie(name)}=${encodeCookie(header.optString(name))}"
        }
    }

    fun absorb(setCookieHeaders: List<String>, cookieString: String) {
        val current = getCookies()
        var changed = false
        val lines = setCookieHeaders.map { it.substringBefore(';') } +
            cookieString.split(';')
        lines.forEach { raw ->
            val idx = raw.indexOf('=')
            if (idx <= 0) return@forEach
            val name = raw.substring(0, idx).trim()
            val value = raw.substring(idx + 1).trim()
            if (name !in allowedCookies || value.length > 5000) return@forEach
            if (value.isEmpty() || value.equals("deleted", true)) current.remove(name)
            else current.put(name, value)
            changed = true
        }
        if (changed) saveCookies(current)
    }

    private fun getCookies(): JSONObject {
        val value = prefs.getString("session", null) ?: return JSONObject()
        return runCatching {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            require(bytes.size >= 13)
            val iv = bytes.copyOfRange(0, 12)
            val ciphertext = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        }.getOrElse { JSONObject() } // Decryption may fail after device backup/restore.
    }

    private fun saveCookies(cookies: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore disallows a caller-supplied IV for GCM encryption.
        // Let Keystore generate a fresh random IV, then persist it with the ciphertext.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = requireNotNull(cipher.iv) { "Android Keystore 未返回 AES-GCM IV" }
        require(iv.size == 12) { "AES-GCM IV 长度不正确：${iv.size}" }
        val ciphertext = cipher.doFinal(cookies.toString().toByteArray(Charsets.UTF_8))
        // The existing getCookies() decoder expects [12-byte IV][ciphertext + GCM tag].
        val encoded = Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
        prefs.edit().putString("session", encoded).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encodeCookie(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20")

    companion object { private const val KEY_ALIAS = "qstyle_netease_cookie_aes_v1" }
}
