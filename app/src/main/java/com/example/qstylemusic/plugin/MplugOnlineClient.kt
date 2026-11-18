package com.example.qstylemusic.plugin

import com.example.qstylemusic.plugin.auth.QqMusicSession
import org.json.JSONArray
import org.json.JSONObject

/** Network transport and the source-specific JSON adapters stay separate. */
class MplugOnlineClient(
    private val store: MplugStore,
    private val runtime: MplugJsRuntime = MplugJsRuntime(),
    private val http: MplugHttpClient = MplugHttpClient(),
    private val qqSession: QqMusicSession? = null
) {
    suspend fun call(id: String, action: String, params: JSONObject, baseUrl: String): String {
        val archive = store.loadEnabled(id)
        require(archive.manifest.apiVersion == 2 && "http.request" in archive.manifest.permissions) {
            "需要 V0.7 网络音源插件"
        }
        require(action in setOf("search", "lyrics", "resolveStream", "playlist") &&
            action in archive.manifest.capabilities) { "插件未声明 $action 能力" }
        val envelope = JSONObject().put("action", action).put("params", params)
        val request = JSONObject(runtime.call(archive, "buildRequest", envelope))

        // This built-in DIRECT adapter is pinned to NetEase's HTTPS origin.
        // A .mplug may choose only an approved relative path for its declared action.
        // Existing self-hosted .mplug sources continue to use the configured baseUrl.
        val targetOrigin = when (id) {
            "netease.direct" -> {
                val expectedPath = when (action) {
                    "search" -> "/api/search/get/web"
                    "lyrics" -> "/api/song/lyric"
                    "playlist" -> "/api/playlist/detail"
                    "resolveStream" -> "/api/song/enhance/player/url"
                    else -> error("不允许的网易云操作")
                }
                require(request.optString("path") == expectedPath) {
                    "网易云直连插件请求了不允许的接口"
                }
                "https://music.163.com"
            }
            "qqmusic.direct" -> {
                // QQ's songmid is alphanumeric; never give the JS arbitrary origins,
                // credentials, or unrestricted modules from QQ Music's API gateway.
                validateQqMusicRequest(action, request)
                if (action == "lyrics") "https://c.y.qq.com" else "https://u.y.qq.com"
            }
            else -> baseUrl
        }
        // The script produces an anonymous, strictly validated request. The native host
        // may then attach the locally saved QQ login session. No secrets reach QuickJS.
        val auth = if (id == "qqmusic.direct") qqSession?.read() else null
        if (auth != null && id == "qqmusic.direct" && action in setOf("playlist", "resolveStream")) {
            val query = request.getJSONObject("query")
            val data = JSONObject(query.getString("data"))
            val comm = data.getJSONObject("comm")
            comm.put("uin", auth.uin).put("authst", auth.musicKey)
            if (action == "resolveStream") {
                data.getJSONObject("req_0").getJSONObject("param").put("uin", auth.uin)
            }
            query.put("data", data.toString())
        }
        val response = http.request(targetOrigin, request, auth)
        // JS only sees response body, never Android objects, credentials or raw HTTP connections.
        return runtime.call(
            archive,
            "parseResponse",
            JSONObject().put("action", action).put("params", params).put("response", response)
        )
    }

    /** Strict allowlist: one QQ endpoint and a fixed read-only method per action. */
    private fun validateQqMusicRequest(action: String, request: JSONObject) {
        val path = request.optString("path")
        val query = request.optJSONObject("query") ?: error("QQ 音乐缺少查询参数")
        if (action == "lyrics") {
            require(path == "/lyric/fcgi-bin/fcg_query_lyric_new.fcg") { "QQ 歌词路径不被允许" }
            require(query.optString("songmid").matches(Regex("[A-Za-z0-9]{6,32}"))) {
                "QQ 歌曲 MID 不合法"
            }
            require(query.optString("format") == "json" && query.optInt("nobase64") == 0) {
                "QQ 歌词参数不被允许"
            }
            require(query.length() == 3) { "QQ 歌词包含额外参数" }
            return
        }
        require(path == "/cgi-bin/musicu.fcg" && query.length() == 1) {
            "QQ 直连仅允许指定的 musicu 查询"
        }
        val data = query.optString("data")
        require(data.isNotEmpty() && data.length <= 500) { "QQ 请求参数大小不正确" }
        val body = JSONObject(data)
        val (key, expectedModule, expectedMethod) = when (action) {
            "search" -> Triple("music.search.SearchCgiService", "music.search.SearchCgiService", "DoSearchForQQMusicDesktop")
            "playlist" -> Triple("music.srfDissInfo.DissInfo", "music.srfDissInfo.DissInfo", "CgiGetDiss")
            "resolveStream" -> Triple("req_0", "vkey.GetVkeyServer", "CgiGetVkey")
            else -> error("QQ 音乐不支持该操作")
        }
        val expectedKeys = if (action == "search") setOf(key) else setOf(key, "comm")
        require(body.keys().asSequence().toSet() == expectedKeys) { "QQ 请求包含额外的 API 模块" }
        if (action != "search") {
            val comm = body.optJSONObject("comm") ?: error("QQ WEB 请求公共参数缺失")
            require(comm.length() == 4 && comm.optString("uin") == "0" &&
                comm.optString("format") == "json" && comm.optInt("ct") == 24 &&
                comm.optInt("cv") == 4747474) { "QQ 请求公共参数不合法" }
        }
        val envelope = body.optJSONObject(key) ?: error("QQ 请求模块不正确")
        require(envelope.optString("module") == expectedModule &&
            envelope.optString("method") == expectedMethod) { "QQ 请求操作不被允许" }
        val params = envelope.optJSONObject("param") ?: error("QQ 请求参数缺失")
        when (action) {
            "search" -> {
                require(params.keys().asSequence().toSet() == setOf(
                    "query", "search_type", "page_num", "num_per_page"
                )) { "QQ 搜索存在多余参数" }
                require(params.optString("query").length in 1..80 &&
                    params.optInt("search_type") == 0 && params.optInt("page_num") == 1 &&
                    params.optInt("num_per_page") in 1..20) { "QQ 搜索参数不合法" }
            }
            "playlist" -> {
                require(params.keys().asSequence().toSet() == setOf(
                    "disstid", "dirid", "song_begin", "song_num", "tag", "userinfo", "orderlist", "onlysonglist"
                )) { "QQ 歌单存在多余参数" }
                require(params.optLong("disstid") > 0L && params.optInt("dirid") == 0 &&
                    params.optInt("song_begin") == 0 && params.optInt("song_num") in 1..100) {
                    "QQ 歌单参数不合法"
                }
            }
            "resolveStream" -> {
                require(params.keys().asSequence().toSet() == setOf(
                    "filename", "guid", "loginflag", "platform", "songmid", "songtype", "uin"
                )) { "QQ 播放请求存在多余参数" }
                val ids = params.optJSONArray("songmid") ?: error("QQ 播放 MID 缺失")
                val names = params.optJSONArray("filename") ?: error("QQ 音频文件名缺失")
                require(ids.length() in 1..4 && ids.length() == names.length()) { "QQ 播放请求数量不合法" }
                for (i in 0 until ids.length()) {
                    val mid = ids.optString(i)
                    require(mid.matches(Regex("[A-Za-z0-9]{6,32}"))) { "QQ 播放歌曲 MID 不合法" }
                    require(names.optString(i) in setOf(
                        "F000$mid.flac", "M800$mid.mp3", "M500$mid.mp3", "C400$mid.m4a"
                    )) { "QQ 音质文件名不合法" }
                }
                require(params.optString("uin") == "0") { "匿名插件不得提供账号身份" }
            }
        }
    }

    suspend fun search(id: String, keyword: String, baseUrl: String): List<OnlineSongInfo> {
        val json = JSONArray(call(id, "search", JSONObject().put("keyword", keyword), baseUrl))
        return (0 until json.length()).mapNotNull { i ->
            val obj = json.optJSONObject(i) ?: return@mapNotNull null
            val songId = obj.optString("id")
            if (!validSongId(id, songId)) return@mapNotNull null
            OnlineSongInfo(
                id = songId,
                title = obj.optString("title", "未知歌曲"),
                artist = obj.optString("artist", "未知歌手"),
                artworkUrl = obj.optString("artworkUrl").takeIf { it.startsWith("https://") },
                durationMs = obj.optLong("durationMs", 0L)
            )
        }
    }

    private fun validSongId(providerId: String, songId: String): Boolean =
        if (providerId == "qqmusic.direct") songId.matches(Regex("[A-Za-z0-9]{6,32}"))
        else songId.matches(Regex("[0-9]{1,19}"))

    suspend fun playlist(id: String, playlistId: String, baseUrl: String): Pair<String, List<OnlineSongInfo>> {
        val result = JSONObject(call(id, "playlist", JSONObject().put("id", playlistId), baseUrl))
        val songs = result.optJSONArray("songs") ?: JSONArray()
        val normalized = (0 until songs.length()).mapNotNull { index ->
            val item = songs.optJSONObject(index) ?: return@mapNotNull null
            val songId = item.optString("id")
            if (!validSongId(id, songId)) return@mapNotNull null
            OnlineSongInfo(
                id = songId,
                title = item.optString("title", "未知歌曲"),
                artist = item.optString("artist", "未知歌手"),
                artworkUrl = item.optString("artworkUrl").takeIf { it.startsWith("https://") },
                durationMs = item.optLong("durationMs")
            )
        }
        return result.optString("name", if (id == "qqmusic.direct") "QQ 音乐歌单" else "网易云歌单") to normalized
    }

    suspend fun lyrics(id: String, songId: String, baseUrl: String): String? {
        val obj = JSONObject(call(id, "lyrics", JSONObject().put("id", songId), baseUrl))
        return obj.optString("lrc").takeIf { it.isNotBlank() }
    }

    suspend fun stream(id: String, songId: String, baseUrl: String): String {
        val obj = JSONObject(call(id, "resolveStream", JSONObject().put("id", songId), baseUrl))
        if (!obj.optBoolean("available", false)) {
            error(obj.optString("reason", "这首歌曲当前没有可播放的授权音源"))
        }
        val streamUrl = obj.optString("url")
        require(streamUrl.startsWith("https://")) {
            "当前仅允许 HTTPS 音频地址；拒绝不安全的或无效的音频地址"
        }
        if (id == "qqmusic.direct") {
            val host = java.net.URI(streamUrl).host?.lowercase().orEmpty()
            require(host.endsWith(".qqmusic.qq.com")) { "QQ 播放地址来源不可信" }
        }
        return streamUrl
    }
}

data class OnlineSongInfo(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val durationMs: Long
)
