package com.example.qstylemusic.plugin.auth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/**
 * 将 QQ 音乐扫码会话、歌单和音频 URL 获取封装成 Kotlin 数据能力。
 * 兼容非官方 yakult-green-tea/qq-music-api 的 3.3.x API 路径。
 *
 * 不使用 WebView User-Agent 伪装；不会传递会话给插件 JS 或音频 CDN。
 * 注意：上游不保证所有歌曲一定会返回播放 URL。
 */
class QqMusicBridgeApi(context: Context, backendUrl: String) {
    private val backend = trustedServiceUrl(backendUrl)
    private val session = QqMusicBridgeSession(context.applicationContext)

    data class Challenge(val key: String, val qrImage: String)
    enum class QrState { WAITING, SCANNED, AUTHORIZED, EXPIRED }
    data class QrPoll(val state: QrState, val message: String = "")
    data class Playlist(
        val id: String, val title: String, val coverUrl: String?,
        val count: Int, val dirId: Long = 0L
    )
    data class Track(
        val mid: String, val mediaMid: String?, val title: String, val artist: String,
        val album: String, val coverUrl: String?
    )
    data class PlayUrl(val url: String, val isPreview: Boolean)

    val hasSession: Boolean get() = session.read() != null

    /** 地址由用户自己的 PC API 或受信任的 HTTPS API 服务提供。 */
    private fun trustedServiceUrl(value: String): String {
        val uri = URI(value.trim().trimEnd('/'))
        require(uri.scheme == "https" || uri.scheme == "http") { "必须使用 http(s) 服务地址" }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "服务地址无效"
        }
        return uri.toString().trimEnd('/')
    }

    private data class ApiReply(val json: JSONObject, val cookie: String?)

    private suspend fun request(
        path: String, args: Map<String, String> = emptyMap(),
        authenticated: Boolean = false, jsonPost: JSONObject? = null
    ): ApiReply = withContext(Dispatchers.IO) {
        val query = if (args.isEmpty()) "" else args.entries.joinToString("&", prefix = "?") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val target = URL("$backend/${path.trimStart('/')}$query")
        val connection = target.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 25_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (authenticated) {
                val token = session.read() ?: error("请先完成 QQ 音乐扫码登录")
                connection.setRequestProperty("X-QQ-Session", token)
            }
            if (jsonPost != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(jsonPost.toString().toByteArray(Charsets.UTF_8)) }
            }
            val http = connection.responseCode
            // 不把返回报文、Session、原始 CDN URL 写入日志。
            if (http !in 200..299) throw IllegalStateException("QQ 接口 HTTP $http")
            val text = ByteArrayOutputStream().use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(output.size() + n <= 4 * 1024 * 1024) { "接口响应过大" }
                        output.write(buffer, 0, n)
                    }
                }
                output.toString("UTF-8")
            }
            val cookie = connection.headerFields.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value.orEmpty() }
                .mapNotNull { it.split(';').firstOrNull()?.trim() }
                .firstOrNull { it.startsWith("qqmusic_session=") }
                ?.substringAfter('=')?.takeIf { it.isNotBlank() }
            ApiReply(JSONObject(text), cookie)
        } finally { connection.disconnect() }
    }

    suspend fun createChallenge(channel: String = "qq"): Challenge {
        require(channel in setOf("qq", "qqmusic", "wechat"))
        val keyData = request("login/qr/key", mapOf("channel" to channel)).json.optJSONObject("data")
        val key = keyData?.optString("unikey").orEmpty()
            .ifBlank { keyData?.optString("key").orEmpty() }
        require(key.isNotBlank()) { "QQ 扫码接口未返回二维码会话" }
        val qr = request("login/qr/create", mapOf("key" to key))
            .json.optJSONObject("data")?.optString("qrimg").orEmpty()
        require(qr.startsWith("data:image/") && qr.contains(";base64,")) { "二维码数据格式发生变化" }
        return Challenge(key, qr)
    }

    suspend fun pollChallenge(key: String): QrPoll {
        val result = request("login/qr/check", mapOf("key" to key))
        val data = result.json.optJSONObject("data")
        val codes = listOf(
            data?.optInt("status", -1) ?: -1,
            data?.optInt("code", -1) ?: -1,
            result.json.optInt("status", -1),
            result.json.optInt("code", -1)
        )
        val status = codes.firstOrNull { it in 800..803 } ?: -1
        return when (status) {
            801 -> QrPoll(QrState.WAITING, "等待扫码")
            802 -> QrPoll(QrState.SCANNED, "已扫码，请在手机上确认")
            803 -> {
                val token = data?.optString("token").orEmpty()
                    .ifBlank { data?.optJSONObject("session")?.optString("token").orEmpty() }
                    .ifBlank { result.cookie.orEmpty() }
                require(token.isNotBlank()) { "已确认登录，但未收到会话 Token，请检查后端兼容版本" }
                session.save(token)
                QrPoll(QrState.AUTHORIZED, "登录成功")
            }
            800 -> QrPoll(QrState.EXPIRED,
                data?.optString("failureHint").orEmpty().ifBlank { "二维码失效，请重新生成" })
            else -> error("QQ 扫码服务返回未知状态")
        }
    }

    suspend fun cancelChallenge(key: String) {
        runCatching { request("login/qr/cancel", mapOf("key" to key)) }
    }

    suspend fun logout() {
        try { if (hasSession) request("logout", authenticated = true) }
        finally { session.clear() }
    }

    /** 只发送 challenge ID 到 PC 局域网展示端，不发送会话 Token。 */
    suspend fun displayOnPc(challenge: Challenge, pcUrl: String, displayKey: String) =
        withContext(Dispatchers.IO) {
            require(displayKey.isNotBlank()) { "请填写电脑展示口令" }
            val pc = trustedServiceUrl(pcUrl)
            val conn = URL("$pc/challenge").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 6000
                conn.readTimeout = 12000
                conn.requestMethod = "POST"
                conn.instanceFollowRedirects = false
                conn.doOutput = true
                conn.setRequestProperty("X-Display-Key", displayKey)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(JSONObject().put("key", challenge.key).toString()
                    .toByteArray(Charsets.UTF_8)) }
                require(conn.responseCode in 200..299) { "电脑展示服务 HTTP ${conn.responseCode}" }
            } finally { conn.disconnect() }
        }

    /** 读取当前登录账号的歌单；特设一个“我喜欢”入口便于保持原有界面。 */
    suspend fun playlists(): List<Playlist> {
        val root = request("user/playlist", authenticated = true).json
        val collected = mutableListOf<Playlist>()
        collectPlaylists(root, collected)
        val liked = Playlist("__liked__", "我喜欢", null, 0)
        return (listOf(liked) + collected.filterNot {
            it.title == "我喜欢" || it.dirId == 201L || it.id == "201"
        }).distinctBy { it.id }
    }

    private fun collectPlaylists(node: Any?, output: MutableList<Playlist>, depth: Int = 0) {
        if (depth > 6) return
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) collectPlaylists(node.opt(i), output, depth + 1)
            is JSONObject -> {
                val title = node.firstText("dirName", "diss_name", "name", "title")
                val tid = node.firstText("tid", "dissid", "playlistId", "id")
                val dirId = node.optLong("dirid", node.optLong("dirId", 0L))
                if (title.isNotBlank() && (tid.isNotBlank() || dirId > 0)) {
                    val id = if (tid.isNotBlank() && tid != "0") tid else "dir:$dirId"
                    output.add(Playlist(
                        id, title,
                        safeArt(node.firstText("picUrl", "diss_cover", "coverUrl", "cover")),
                        node.optInt("songNum", node.optInt("songCount", node.optInt("song_cnt", 0))),
                        dirId
                    ))
                    return
                }
                for (key in listOf("data", "list", "playlists", "created", "favorite", "favorites",
                    "collected", "v_playlist", "disslist", "owned", "subscribed")) {
                    collectPlaylists(node.opt(key), output, depth + 1)
                }
            }
        }
    }

    suspend fun playlistTracks(playlist: Playlist, offset: Int = 0, limit: Int = 100): List<Track> {
        require(offset >= 0 && limit in 1..100)
        val args = mapOf("offset" to offset.toString(), "limit" to limit.toString())
        val root = if (playlist.id == "__liked__") {
            request("user/liked-songs", args, authenticated = true).json
        } else {
            val ref = if (playlist.id.startsWith("dir:"))
                mapOf("dirid" to playlist.id.removePrefix("dir:"))
            else mapOf("tid" to playlist.id)
            request("user/playlist-detail", args + ref, authenticated = true).json
        }
        val list = locateTracks(root) ?: error("接口没有返回可识别的歌曲列表，可能需要更新解析字段")
        return (0 until list.length()).mapNotNull { parseTrack(list.optJSONObject(it)) }
    }

    private fun locateTracks(node: Any?, depth: Int = 0): JSONArray? {
        if (depth > 6) return null
        if (node is JSONArray) return node
        if (node !is JSONObject) return null
        for (key in listOf("songs", "songlist", "songList", "tracks", "list", "items", "musicList", "data")) {
            locateTracks(node.opt(key), depth + 1)?.let { return it }
        }
        return null
    }

    private fun parseTrack(source: JSONObject?): Track? {
        val item = source?.optJSONObject("songInfo")
            ?: source?.optJSONObject("song")
            ?: source?.optJSONObject("track")
            ?: source?.optJSONObject("musicData") ?: source ?: return null
        val mid = item.firstText("mid", "songmid", "songMid")
        if (mid.isBlank()) return null
        val singer = item.optJSONArray("singer") ?: item.optJSONArray("artists") ?: JSONArray()
        val artist = (0 until singer.length()).mapNotNull { idx ->
            singer.optJSONObject(idx)?.firstText("name", "title")?.takeIf { it.isNotBlank() }
        }.joinToString(" / ").ifBlank { item.firstText("artist", "singerName").ifBlank { "未知歌手" } }
        val album = item.optJSONObject("album")
        val albumMid = album?.firstText("mid", "albummid").orEmpty()
        return Track(
            mid = mid,
            mediaMid = item.optJSONObject("file")?.firstText("media_mid", "mediaMid")
                ?.takeIf { it.isNotBlank() },
            title = item.firstText("name", "songname", "title").ifBlank { "未知歌曲" },
            artist = artist,
            album = album?.firstText("name", "title").orEmpty(),
            coverUrl = albumMid.takeIf { it.isNotBlank() }?.let {
                "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg"
            }
        )
    }

    suspend fun resolvePlayback(songMid: String, mediaMid: String? = null): PlayUrl {
        require(songMid.matches(Regex("[A-Za-z0-9]{6,32}"))) { "歌曲 MID 不合法" }
        val params = mutableMapOf("quality" to "best", "resType" to "play")
        mediaMid?.takeIf { it.matches(Regex("[A-Za-z0-9]{6,32}")) }
            ?.let { params["mediaId"] = it }
        val root = request("getMusicPlay/$songMid", params, authenticated = true).json
        val playUrl = root.optJSONObject("data")?.optJSONObject("playUrl")
            ?: root.optJSONObject("playUrl")
        val item = playUrl?.optJSONObject(songMid)
            ?: playUrl?.takeIf { it.has("url") }
        val audio = item?.optString("url").orEmpty()
        if (audio.isBlank()) {
            val hint = item?.optJSONObject("restriction")?.optJSONObject("hint")
                ?.optString("message").orEmpty()
            error("QQ 音乐未返回可用播放地址" +
                if (hint.isBlank()) "（原因尚未确认，不能断定会员或地区限制）" else "：$hint")
        }
        val details = item?.optJSONObject("source")
        require(details?.optString("encryption") != "encrypted" &&
            details?.optString("protocol") != "encrypted-evkey") {
            "当前歌曲返回受保护的加密音频，普通 Media3 无法直接播放"
        }
        val uri = URI(audio)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "播放服务返回非 HTTPS 音频地址" }
        return PlayUrl(audio, item?.optBoolean("preview") == true || details?.optString("kind") == "preview")
    }

    private fun safeArt(url: String): String? = when {
        url.startsWith("https://") -> url
        url.startsWith("http://") -> url.replaceFirst("http://", "https://")
        else -> null
    }

    private fun JSONObject.firstText(vararg keys: String): String {
        for (key in keys) {
            val value = optString(key).trim()
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }
}
