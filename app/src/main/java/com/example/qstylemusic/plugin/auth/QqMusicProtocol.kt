package com.example.qstylemusic.plugin.auth

import java.net.URI
import java.net.URLDecoder

/**
 * Platform-independent parts of QQ's publicly observed QR/OAuth flow.
 * No password, device ID, private key, or music credential is logged here.
 */
internal object QqMusicProtocol {
    data class PtuiCallback(val status: Int, val redirectUrl: String?)

    /** QQ ptqrlogin's ptqrtoken: hash33 of the qrsig cookie. */
    fun hash33(value: String): Int {
        var hash = 0L
        value.forEach { character ->
            hash = (hash * 33L + character.code) and 0x7fffffffL
        }
        return hash.toInt()
    }

    /** g_tk for QQ OAuth. */
    fun gtk(value: String): Int {
        var hash = 5381L
        value.forEach { character ->
            hash = (hash * 33L + character.code) and 0x7fffffffL
        }
        return hash.toInt()
    }

    /**
     * QQ returns ptuiCB(status, ext, redirect, ...).
     * The redirect can use HTTP even though all our network requests use HTTPS.
     * Capture the third argument rather than scanning for any HTTPS string.
     */
    fun ptuiCallback(body: String): PtuiCallback {
        require(body.length <= 16_384) { "QQ 登录回调过长" }
        val match = Regex("""ptuiCB\(\s*'([0-9]+)'\s*,\s*'[^']*'\s*,\s*'([^']*)'""")
            .find(body) ?: error("QQ 扫码回调格式已改变")
        val status = match.groupValues[1].toInt()
        val url = match.groupValues[2]
            .replace("&amp;", "&")
            .replace("\\/", "/")
            .takeIf { it.isNotBlank() }
        if (status == 0 && url == null) {
            error("QQ 扫码已确认，但认证回调没有跳转地址")
        }
        return PtuiCallback(status, url)
    }

    /** Parses Set-Cookie safely, including combined headers containing Expires=Wed, ... . */
    fun cookiePairs(headers: Collection<String>): Map<String, String> {
        val found = linkedMapOf<String, String>()
        headers.forEach { header ->
            val parts = header.split(Regex(""",(?=\s*[A-Za-z_][A-Za-z0-9_]*=)"""))
            for (part in parts) {
                val cookie = part.trim().substringBefore(';').trim()
                val equals = cookie.indexOf('=')
                if (equals <= 0) continue
                val name = cookie.substring(0, equals).trim()
                val value = cookie.substring(equals + 1).trim()
                if (name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) &&
                    value.isNotEmpty() && value.length <= 8192 &&
                    '\r' !in value && '\n' !in value) {
                    found[name] = value
                }
            }
        }
        return found
    }

    /**
     * Never fetch an arbitrary QR callback or send cookies over HTTP.
     * Some QQ ptlogin variants return http://ptlogin2.../check_sig; validate
     * the exact HTTPS-capable host and endpoint before upgrading to HTTPS.
     * Query parameters are preserved verbatim (may contain authorization data).
     */
    fun checkedSigUrl(raw: String): String {
        val uri = try {
            URI(raw.trim())
        } catch (_: Exception) {
            throw IllegalArgumentException("QQ 登录认证跳转地址格式异常")
        }
        val host = uri.host?.lowercase()
        val scheme = uri.scheme?.lowercase()
        val acceptedHosts = setOf(
            "ptlogin2.qq.com", "ssl.ptlogin2.qq.com",
            "ptlogin2.graph.qq.com", "ssl.ptlogin2.graph.qq.com"
        )
        require((scheme == "http" || scheme == "https") &&
            host in acceptedHosts && uri.path == "/check_sig" &&
            uri.userInfo == null && uri.port == -1 && uri.rawFragment == null &&
            (uri.rawQuery?.length ?: 0) <= 12_000) {
            // Do not include the query string: it can contain QQ login tickets.
            "QQ 登录认证跳转地址不可信（域名：${host?.take(80) ?: "未知"}，路径：${uri.path?.take(60) ?: "空"}）"
        }
        return "https://$host/check_sig" + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    fun oauthCode(location: String?): String {
        require(!location.isNullOrEmpty()) { "QQ OAuth 未提供跳转地址" }
        val uri = URI(location)
        require(uri.scheme == "https" && uri.userInfo == null &&
            uri.host?.lowercase() in setOf("y.qq.com", "graph.qq.com")) {
            "QQ OAuth 跳转域名不可信"
        }
        val raw = uri.rawQuery?.split('&').orEmpty().firstOrNull { it.startsWith("code=") }
            ?.substringAfter('=') ?: error("QQ OAuth 未返回授权 code")
        val code = URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        require(code.matches(Regex("[A-Za-z0-9._~+-]{8,512}"))) {
            "QQ OAuth code 格式异常"
        }
        return code
    }
}
