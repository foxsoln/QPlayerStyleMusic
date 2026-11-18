package com.example.qstylemusic.plugin.auth

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * QQ lyric transport that runs entirely on the Android device.
 * The host owns credentials; .mplug JavaScript never receives QQ cookies.
 * Only fixed QQ HTTPS hosts and one read-only lyric path are accessed.
 */
class QqMusicLyricClient(context: Context) {
    private val session = QqMusicSession(context.applicationContext)
    private val lrcCache = ConcurrentHashMap<String, String>()

    data class Result(val lrc: String?, val diagnostic: String = "")
    private data class Route(val host: String, val noBase64: Boolean)

    /** Requests the LRC timeline by song MID, *not* by media_mid. */
    suspend fun lyrics(songMid: String): Result {
        require(songMid.matches(Regex("[A-Za-z0-9]{6,32}"))) { "QQ 歌曲 MID 不合法" }
        lrcCache[songMid]?.let { return Result(it) }
        return withTimeoutOrNull(11_000L) {
            withContext(Dispatchers.IO) {
                val credentials = session.read()
                val cookie = credentials?.cookieHeader().orEmpty()
                val uin = credentials?.uin ?: "0"
                // The first route returns readable LRC on compatible QQ Web endpoints.
                // A base64 route is retained for accounts that receive encoded lyrics.
                val routes = listOf(
                    Route("c.y.qq.com", true),
                    Route("i.y.qq.com", true),
                    Route("c.y.qq.com", false)
                )
                val reports = mutableListOf<String>()
                for (route in routes) {
                    try {
                        val body = getLyricResponse(route, songMid, uin, cookie)
                        val status = if (body.has("retcode")) body.optInt("retcode", -1)
                            else body.optInt("code", -1)
                        val text = QqMusicLyricCodec.normalize(body.optString("lyric"))
                        if (status == 0 && text != null) {
                            lrcCache[songMid] = text
                            return@withContext Result(text)
                        }
                        reports += "${route.host}: code=$status, LRC=${if (text != null) "有" else "无"}"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Never leak URL query fields, cookies or tokens into the UI.
                        reports += "${route.host}: 请求失败(${e.javaClass.simpleName})"
                    }
                }
                Result(null, reports.joinToString("；").take(240))
            }
        } ?: Result(null, "QQ 歌词接口请求超时")
    }

    private fun getLyricResponse(
        route: Route,
        songMid: String,
        uin: String,
        cookie: String
    ): JSONObject {
        val query = linkedMapOf(
            "songmid" to songMid,
            "format" to "json",
            "nobase64" to if (route.noBase64) "1" else "0",
            "g_tk" to "5381",
            "loginUin" to uin,
            "hostUin" to "0",
            "inCharset" to "utf8",
            "outCharset" to "utf-8",
            "notice" to "0",
            "platform" to "yqq.json",
            "needNewCode" to "0"
        )
        val encoded = query.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" 
        }
        val url = URL("https://${route.host}/lyric/fcgi-bin/fcg_query_lyric_new.fcg?$encoded")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_500
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Referer", "https://y.qq.com/portal/player.html")
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36")
            if (cookie.isNotEmpty()) conn.setRequestProperty("Cookie", cookie)
            val code = conn.responseCode
            require(code in 200..299) { "HTTP $code" }
            val bytes = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(bytes.size() + n <= 1024 * 1024) { "歌词响应超过大小限制" }
                    bytes.write(buffer, 0, n)
                }
            }
            val raw = bytes.toString("UTF-8").trim().removePrefix("\uFEFF").trim()
            // The endpoint may return a JSONP wrapper despite format=json.
            val json = if (raw.startsWith("{")) raw else {
                val start = raw.indexOf('{')
                val end = raw.lastIndexOf('}')
                require(start in 0 until end && raw.take(start).matches(Regex("[A-Za-z0-9_.$(\\s]*"))) {
                    "不是 JSON 歌词响应"
                }
                raw.substring(start, end + 1)
            }
            return JSONObject(json)
        } finally {
            conn.disconnect()
        }
    }
}
