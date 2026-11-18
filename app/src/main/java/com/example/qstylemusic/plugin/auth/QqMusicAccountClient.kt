package com.example.qstylemusic.plugin.auth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * QQ Music account read-only adapter for the host. JavaScript plugins never see cookies.
 * The upstream Web endpoints are unofficial and may change or enforce verification.
 */
class QqMusicAccountClient(context: Context) {
    val session = QqMusicSession(context.applicationContext)
    val hasLocalSession: Boolean get() = session.hasLocalSession
    private val mediaMidCache = ConcurrentHashMap<String, String>()

    data class Profile(val uin: String, val displayName: String, val avatarUrl: String)
    data class Playlist(
        val id: String,
        val name: String,
        val coverUrl: String?,
        val songCount: Int,
        val dirId: Long = 0L
    )
    data class Track(
        val mid: String,
        val name: String,
        val artist: String,
        val album: String,
        val artworkUrl: String?,
        // QQ 返回的真实媒体文件标识有时与歌曲 MID 不同；播放取链应优先使用该字段。
        val mediaMid: String? = null
    )

    fun profile(): Profile? = session.read()?.let { data ->
        Profile(data.uin, "QQ 音乐 · ${data.uin}",
            "https://q.qlogo.cn/headimg_dl?dst_uin=${data.uin}&spec=140")
    }

    /** The authenticated PlaylistBaseRead endpoint can return created and collected playlists. */
    suspend fun myPlaylists(): List<Playlist> {
        val auth = requireSession()
        val body = JSONObject()
            .put("comm", comm(auth))
            .put("music.musicasset.PlaylistBaseRead", JSONObject()
                .put("module", "music.musicasset.PlaylistBaseRead")
                .put("method", "GetPlaylistByUin")
                .put("param", JSONObject().put("uin", auth.uin)))
        val response = postMusicU(body, auth)
        val section = response.optJSONObject("music.musicasset.PlaylistBaseRead")
            ?: error("QQ 音乐歌单接口响应结构已改变")
        require(section.optInt("code", 0) == 0) {
            "QQ 音乐歌单接口 code=${section.optInt("code")}; 可能需要重新登录"
        }
        val initial = section.optJSONObject("data") ?: JSONObject()
        val dataPart = initial.optJSONObject("data") ?: initial
        val list = dataPart.optJSONArray("v_playlist") ?: JSONArray()
        val playlists = mutableListOf<Playlist>()
        for (i in 0 until list.length()) {
            val p = list.optJSONObject(i) ?: continue
            val tid = p.optString("tid").takeIf { it.matches(Regex("[0-9]{1,19}")) }.orEmpty()
            val dirId = p.optLong("dirId", p.optLong("dirid", 0L))
            val name = p.optString("dirName", p.optString("title", "QQ 歌单"))
            val id = if (tid.isNotBlank() && tid != "0") tid
                else if (dirId > 0) "dir:$dirId" else continue
            playlists.add(
                Playlist(
                    id = id,
                    name = name,
                    coverUrl = safeArt(p.optString("picUrl", p.optString("coverUrl"))),
                    songCount = p.optInt("songNum", p.optInt("songCount", 0)),
                    dirId = dirId
                )
            )
        }
        // The endpoint may legitimately return zero playlists, but we don't misreport that as
        // a login failure. Another QQ Web endpoint can be added later if needed for collections.
        // Some accounts return their created playlists only through the older read API.
        // Merge them when possible without treating a failed fallback as a login failure.
        val created = runCatching { createdPlaylists(auth) }.getOrDefault(emptyList())
        return (playlists + created).distinctBy { it.id }
            .sortedByDescending { it.dirId == 201L }
    }

    private suspend fun createdPlaylists(auth: QqMusicSession.Credentials): List<Playlist> =
        withTimeout(10_000L) {
            withContext(Dispatchers.IO) {
                val url = URL("https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss" +
                    "?hostuin=${URLEncoder.encode(auth.uin, "UTF-8")}&sin=0&size=100&format=json")
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 4_000
                    conn.readTimeout = 5_000
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("Referer", "https://y.qq.com/")
                    conn.setRequestProperty("Cookie", auth.cookieHeader())
                    require(conn.responseCode in 200..299)
                    val result = JSONObject(readBounded(conn))
                    val list = result.optJSONObject("data")?.optJSONArray("disslist") ?: JSONArray()
                    (0 until list.length()).mapNotNull { idx ->
                        val item = list.optJSONObject(idx) ?: return@mapNotNull null
                        val id = item.optString("tid")
                        if (!id.matches(Regex("[0-9]{1,19}"))) return@mapNotNull null
                        Playlist(id, item.optString("diss_name", "QQ 歌单"),
                            safeArt(item.optString("diss_cover")), item.optInt("song_cnt", 0))
                    }
                } finally { conn.disconnect() }
            }
        }

    /** Read one page at a time so a large playlist does not block the UI. */
    suspend fun playlistTracks(playlist: Playlist, offset: Int = 0, count: Int = 100): List<Track> {
        require(offset >= 0 && count in 1..100)
        val auth = requireSession()
        val dir = if (playlist.id.startsWith("dir:")) playlist.dirId else 0L
        val diss = if (dir == 0L) playlist.id.toLongOrNull() ?: 0L else 0L
        require(diss > 0L || dir > 0L) { "QQ 歌单 ID 不合法" }
        val params = JSONObject()
            .put("disstid", diss)
            .put("dirid", dir)
            .put("song_begin", offset)
            .put("song_num", count)
            .put("tag", true)
            .put("userinfo", true)
            .put("orderlist", true)
            .put("onlysonglist", false)
        val result = postMusicU(
            JSONObject().put("comm", comm(auth))
                .put("music.srfDissInfo.DissInfo", JSONObject()
                    .put("module", "music.srfDissInfo.DissInfo")
                    .put("method", "CgiGetDiss")
                    .put("param", params)),
            auth
        )
        val section = result.optJSONObject("music.srfDissInfo.DissInfo")
            ?: error("QQ 歌单详情响应结构已改变")
        require(section.optInt("code", 0) == 0) {
            "QQ 歌单读取失败：code=${section.optInt("code")}" 
        }
        val data = section.optJSONObject("data") ?: JSONObject()
        val tracks = data.optJSONArray("songlist") ?: data.optJSONArray("songList") ?: JSONArray()
        return (0 until tracks.length()).mapNotNull { index -> mapTrack(tracks.optJSONObject(index)) }
    }

    /**
     * Request the account's own entitled stream from QQ Music. No proxy, external backend,
     * alternate provider, or decryption is involved. A successful login alone does not
     * guarantee a specific track or quality is licensed for this account/region.
     *
     * On failures, only response status/subcodes and counts are displayed: never the
     * encrypted session token, playback purl, cookies, or signed CDN URL.
     */
    suspend fun streamUrl(songMid: String, mediaMid: String? = null): String {
        require(isMid(songMid)) { "QQ 歌曲 MID 不合法" }
        val auth = requireSession()
        val supplied = mediaMid?.takeIf(::isMid)
        val actualFileMid = supplied ?: mediaMidCache[songMid] ?: try {
            queryMediaMid(songMid, auth)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: songMid

        val formats = listOf("F000" to ".flac", "M800" to ".mp3",
            "M500" to ".mp3", "C400" to ".m4a")
        val filenames = formats.map { (prefix, suffix) -> "$prefix$actualFileMid$suffix" }
        val guid = newGuid()

        val reports = mutableListOf<String>()
        // Standard requests first, then account-authorized compatibility paths.
        // EVkey is attempted only as a last resort; never treat an empty purl as access.
        data class VkeyProbe(val name: String, val explicit: Boolean, val evkey: Boolean)
        val probes = listOf(
            VkeyProbe("标准音质", explicit = true, evkey = false),
            VkeyProbe("自动音质", explicit = false, evkey = false),
            VkeyProbe("兼容音质", explicit = true, evkey = true)
        )
        for (probe in probes) {
            val explicit = probe.explicit
            val param = JSONObject()
                .put("guid", guid)
                .put("songmid", JSONArray().apply { if (explicit) repeat(filenames.size) { put(songMid) } else put(songMid) })
                .put("songtype", JSONArray().apply { if (explicit) repeat(filenames.size) { put(0) } else put(0) })
                .put("uin", auth.uin)
                .put("loginflag", 1)
                .put("platform", "20")
            if (explicit) param.put("filename", JSONArray().apply { filenames.forEach { put(it) } })
            if (probe.evkey) param.put("xcdn", 1)

            val body = JSONObject()
                .put("comm", playbackComm(auth))
                .put("loginUin", auth.uin)
                .put("req_0", JSONObject()
                    .put("module", if (probe.evkey) "music.vkey.GetEVkey" else "vkey.GetVkeyServer")
                    .put("method", if (probe.evkey) "CgiGetEVkey" else "CgiGetVkey")
                    .put("param", param))
            val root = postMusicU(body, auth)
            val section = root.optJSONObject("req_0")
            val data = section?.optJSONObject("data")
            val infos = data?.optJSONArray("midurlinfo") ?: JSONArray()
            val sips = data?.optJSONArray("sip") ?: JSONArray()

            val ordered = (0 until infos.length()).mapNotNull { infos.optJSONObject(it) }
            // Prefer request quality order, not the upstream response's array order.
            val sortedInfos = if (explicit) ordered.sortedWith(compareBy { item ->
                val name = item.optString("filename")
                val index = filenames.indexOf(name)
                if (index >= 0) index else filenames.size
            }) else ordered
            for (item in sortedInfos) {
                val itemCode = if (item.has("result")) item.optInt("result", -1)
                    else item.optInt("code", 0)
                if (itemCode != 0) continue
                val purl = item.optString("purl")
                if (purl.isBlank()) continue
                resolveSafeUrl(purl, sips)?.let { return it }
            }
            val codes = ordered.map { if (it.has("result")) it.optInt("result", -1)
                else it.optInt("code", -1) }.distinct().take(4)
            val subs = ordered.map { it.optInt("subcode", -1) }.distinct().take(4)
            reports += "${probe.name}: " +
                "接口=${section?.optInt("code", -1) ?: -1}, " +
                "总码=${root.optInt("code", -1)}, " +
                "候选=${ordered.size}, 有链接=${ordered.count { it.optString("purl").isNotBlank() }}, " +
                "结果码=$codes, 子码=$subs, CDN=${sips.length()}"
        }
        error("QQ 音乐未返回可播放资源（媒体ID=${if (actualFileMid != songMid) "已确认" else "可能缺失"}）。" +
            reports.joinToString("；") + "。请先用 QQ 音乐官方客户端测试同一首歌。")
    }

    /** Detail lookup helps with playlist variants where file.media_mid was not included. */
    private suspend fun queryMediaMid(songMid: String, auth: QqMusicSession.Credentials): String? {
        val request = JSONObject()
            .put("comm", JSONObject().put("format", "json").put("ct", 19).put("cv", 0))
            .put("req_0", JSONObject()
                .put("module", "music.pf_song_detail_svr")
                .put("method", "get_song_detail_yqq")
                .put("param", JSONObject().put("song_mid", songMid)))
        val response = postMusicU(request, auth)
        if (response.optInt("code", 0) != 0) return null
        val section = response.optJSONObject("req_0") ?: return null
        if (section.optInt("code", 0) != 0) return null
        val info = section.optJSONObject("data")?.optJSONObject("track_info") ?: return null
        return readMediaMid(info)?.also { mediaMidCache[songMid] = it }
    }

    /** Only QQ Music's streaming CDN is accepted; HTTP SIPs are upgraded to HTTPS. */
    private fun resolveSafeUrl(purl: String, sips: JSONArray): String? {
        val path = purl.trim()
        if (path.isEmpty() || path.length > 4096 || '\\' in path || '\r' in path || '\n' in path) return null
        val candidates = if (path.startsWith("https://") || path.startsWith("http://")) {
            listOf(path)
        } else {
            val bases = (0 until sips.length()).map { sips.optString(it) }
                .filter { it.startsWith("https://") || it.startsWith("http://") }
                .ifEmpty { listOf("https://dl.stream.qqmusic.qq.com/") }
            bases.map { it.trimEnd('/') + "/" + path.trimStart('/') }
        }
        for (candidate in candidates) {
            val rawUri = runCatching { URI(candidate) }.getOrNull() ?: continue
            val host = rawUri.host?.lowercase() ?: continue
            val lowerPath = rawUri.path?.lowercase().orEmpty()
            // QMC-encrypted caches are not normal Media3 audio resources.
            if (listOf(".mgg", ".mflac", ".qmc", ".qmc0").any(lowerPath::endsWith)) continue
            if (rawUri.scheme !in setOf("http", "https") || rawUri.userInfo != null ||
                rawUri.port != -1 || rawUri.fragment != null ||
                !(host.endsWith(".qqmusic.qq.com") || host == "aqqmusic.tc.qq.com")) continue
            // String replacement preserves already-percent-encoded signed query values.
            val https = if (rawUri.scheme == "http") candidate.replaceFirst("http://", "https://")
                else candidate
            return https
        }
        return null
    }

    private fun playbackComm(auth: QqMusicSession.Credentials): JSONObject = JSONObject()
        .put("ct", 24)
        .put("cv", 0)
        .put("format", "json")
        .put("uin", auth.uin)
        .put("authst", auth.musicKey)
        .put("tmeLoginType", auth.cookies["login_type"]?.toIntOrNull() ?: 1)

    private val random = SecureRandom()
    private fun newGuid(): String = (1_000_000_000L +
        Math.floorMod(random.nextLong(), 9_000_000_000L)).toString()

    private fun isMid(value: String): Boolean = value.matches(Regex("[A-Za-z0-9]{6,32}"))

    private fun readMediaMid(item: JSONObject): String? {
        val file = item.optJSONObject("file")
        return sequenceOf(file?.optString("media_mid"), file?.optString("mediaMid"),
            item.optString("media_mid"), item.optString("mediaMid"), item.optString("strMediaMid"))
            .filterNotNull().firstOrNull(::isMid)
    }

    private fun mapTrack(source: JSONObject?): Track? {
        val item = (source?.optJSONObject("songInfo") ?: source?.optJSONObject("track") ?: source)
            ?: return null
        val mid = item.optString("mid", item.optString("songmid"))
        if (!mid.matches(Regex("[A-Za-z0-9]{6,32}"))) return null
        val singers = item.optJSONArray("singer") ?: JSONArray()
        val artist = (0 until singers.length()).mapNotNull {
            singers.optJSONObject(it)?.optString("name")?.takeIf { name -> name.isNotBlank() }
        }.joinToString(" / ").ifBlank { "未知歌手" }
        val album = item.optJSONObject("album")
        val albumMid = album?.optString("mid").orEmpty()
        val cover = if (albumMid.matches(Regex("[A-Za-z0-9]{6,32}"))) {
            "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg"
        } else null
        val mediaMid = readMediaMid(item)?.also { mediaMidCache[mid] = it }
        return Track(mid, item.optString("name", item.optString("songname", "未知歌曲")),
            artist, album?.optString("name").orEmpty(), cover, mediaMid)
    }

    private fun safeArt(value: String): String? = when {
        value.startsWith("https://") -> value
        value.startsWith("http://") -> value.replaceFirst("http://", "https://")
        else -> null
    }

    private fun requireSession(): QqMusicSession.Credentials = session.read()
        ?: error("尚未登录 QQ 音乐，请先使用 QQ 扫码登录并完成授权")

    private fun comm(auth: QqMusicSession.Credentials): JSONObject = JSONObject()
        .put("ct", 24).put("cv", 4747474).put("format", "json")
        .put("uin", auth.uin).put("authst", auth.musicKey)

    private suspend fun postMusicU(body: JSONObject, auth: QqMusicSession.Credentials): JSONObject =
        withTimeout(13_000L) {
            withContext(Dispatchers.IO) {
                val connection = URL("https://u.y.qq.com/cgi-bin/musicu.fcg")
                    .openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 7_000
                    connection.instanceFollowRedirects = false
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Referer", "https://y.qq.com/")
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                    connection.setRequestProperty("Cookie", auth.cookieHeader())
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    require(connection.responseCode in 200..299) {
                        "QQ 音乐账号接口 HTTP ${connection.responseCode}"
                    }
                    JSONObject(readBounded(connection))
                } finally {
                    connection.disconnect()
                }
            }
        }

    private fun readBounded(connection: HttpURLConnection): String {
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 1024 * 1024) { "QQ 音乐返回数据过大" }
                output.write(buffer, 0, count)
            }
        }
        return output.toString("UTF-8")
    }
}
