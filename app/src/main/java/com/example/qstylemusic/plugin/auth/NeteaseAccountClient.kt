package com.example.qstylemusic.plugin.auth

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** UI-only models: authentication secrets are never part of these objects. */
data class NeteaseAccountProfile(
    val userId: String,
    val nickname: String,
    val avatarUrl: String?
)

data class NeteaseAccountPlaylist(
    val id: String,
    val name: String,
    val artworkUrl: String?,
    val trackCount: Int,
    val isOwned: Boolean,
    val isFavorite: Boolean,
    val isSubscribed: Boolean
)

data class NeteaseAccountSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String?,
    val durationMs: Long
)

data class NeteaseAccountLibrary(
    val profile: NeteaseAccountProfile,
    val playlists: List<NeteaseAccountPlaylist>,
    val hasMore: Boolean
)

data class NeteaseAccountPlaylistDetail(
    val playlistId: String,
    val name: String,
    val trackCount: Int,
    val songs: List<NeteaseAccountSong>,
    // Optional metadata for the immersive playlist detail header.
    val creatorName: String = "",
    val description: String = "",
    val playCount: Long = 0L
)

/**
 * Native read-only library API built on the SAME session as PC QR login.
 * Nothing here sends Cookie/MUSIC_U to QuickJS or user-configurable endpoints.
 * Upstream APIs are non-public and can change or return risk-control errors.
 */
class NeteaseAccountClient(context: Context) {
    private val transport = NeteaseQrTransport(context.applicationContext)

    val hasLocalSession: Boolean get() = transport.loggedIn

    /** Current account profile for the 'My > more' menu. Never returns cookies. */
    suspend fun profile(): NeteaseAccountProfile {
        val root = transport.executeAccount("/api/nuser/account/get", JSONObject())
        val obj = root.optJSONObject("profile")
            ?: root.optJSONObject("data")?.optJSONObject("profile")
            ?: error("未读取到网易云账号信息，请检查登录是否过期")
        val id = obj.optString("userId")
        require(validId(id)) { "网易云账号 ID 不合法" }
        return NeteaseAccountProfile(
            id,
            obj.optString("nickname", "网易云用户").take(100),
            safeImage(obj.optString("avatarUrl"))
        )
    }

    /** Online song search. A signed-in session stays inside the Kotlin host. */
    suspend fun searchSongs(query: String, limit: Int = 30): List<NeteaseAccountSong> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        require(keyword.length <= 100 && limit in 1..50) { "无效的搜索参数" }
        val body = transport.executeAccount(
            "/api/cloudsearch/pc",
            JSONObject().put("s", keyword).put("limit", limit).put("offset", 0)
        )
        val songs = body.optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
        return (0 until songs.length())
            .mapNotNull { songs.optJSONObject(it)?.let(::parseSong) }
            .distinctBy { it.id }
    }

    /** Account-personalized daily recommendations; requires a valid QR session. */
    suspend fun dailySongs(): List<NeteaseAccountSong> {
        val root = transport.executeAccount("/api/v3/discovery/recommend/songs", JSONObject())
        val songs = root.optJSONObject("data")?.optJSONArray("dailySongs")
            ?: root.optJSONArray("recommend") ?: JSONArray()
        return (0 until minOf(songs.length(), 100))
            .mapNotNull { songs.optJSONObject(it)?.let(::parseSong) }
            .distinctBy { it.id }
    }

    /** Public discovery playlists rendered as square cover cards on Home. */
    suspend fun recommendedPlaylists(limit: Int = 10): List<NeteaseAccountPlaylist> {
        val root = transport.executeAccount(
            "/api/personalized/playlist", JSONObject().put("limit", limit.coerceIn(1, 30))
        )
        val rows = root.optJSONArray("result") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { i ->
            val item = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id")
            if (!validId(id)) return@mapNotNull null
            NeteaseAccountPlaylist(
                id = id,
                name = item.optString("name", "推荐歌单").take(120),
                artworkUrl = safeImage(item.optString("picUrl")
                    .ifBlank { item.optString("coverImgUrl") }),
                trackCount = item.optInt("trackCount", 0).coerceAtLeast(0),
                isOwned = false,
                isFavorite = false,
                isSubscribed = false
            )
        }.distinctBy { it.id }
    }

    suspend fun myLibrary(): NeteaseAccountLibrary {
        val account = transport.executeAccount("/api/nuser/account/get", JSONObject())
        val profile = account.optJSONObject("profile")
            ?: account.optJSONObject("data")?.optJSONObject("profile")
            ?: error("网易云已响应，但未返回账号资料；请重新打开页面或重新扫码")
        val userId = profile.optString("userId")
        require(validId(userId)) { "网易云返回了无效的用户 ID" }
        val info = NeteaseAccountProfile(
            userId = userId,
            nickname = profile.optString("nickname", "网易云用户").take(100),
            avatarUrl = safeImage(profile.optString("avatarUrl"))
        )
        val playlists = mutableListOf<NeteaseAccountPlaylist>()
        var offset = 0
        var more = true
        // Cap auto-pagination to avoid hammering the service if it is misbehaving.
        while (more && offset < 400) {
            val body = transport.executeAccount(
                "/api/user/playlist",
                JSONObject().put("uid", userId).put("limit", 100).put("offset", offset)
            )
            val rows = body.optJSONArray("playlist") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (!validId(id)) continue
                val creatorId = item.optJSONObject("creator")?.optString("userId").orEmpty()
                playlists.add(
                    NeteaseAccountPlaylist(
                        id = id,
                        name = item.optString("name", "未命名歌单").take(120),
                        artworkUrl = safeImage(item.optString("coverImgUrl").ifBlank { item.optString("picUrl") }),
                        trackCount = item.optInt("trackCount", 0).coerceAtLeast(0),
                        isOwned = creatorId == userId,
                        isFavorite = item.optInt("specialType", 0) == 5,
                        isSubscribed = item.optBoolean("subscribed", false)
                    )
                )
            }
            offset += rows.length()
            more = body.optBoolean("more", false) && rows.length() > 0
        }
        // Never rely on translated playlist names to locate "liked songs".
        return NeteaseAccountLibrary(
            info,
            playlists.distinctBy { it.id }.sortedWith(
                compareByDescending<NeteaseAccountPlaylist> { it.isFavorite }
                    .thenByDescending { it.isOwned }
            ),
            hasMore = more
        )
    }

    /** Load playlist tracks in original order, incrementally on demand. */
    suspend fun playlistDetail(id: String, maxTracks: Int = 300): NeteaseAccountPlaylistDetail {
        require(validId(id)) { "歌单 ID 不合法" }
        val root = transport.executeAccount(
            "/api/v6/playlist/detail", JSONObject().put("id", id)
        )
        val playlist = root.optJSONObject("playlist") ?: error("歌单详情为空或无权访问")
        val rows = playlist.optJSONArray("tracks") ?: JSONArray()
        val ids = playlist.optJSONArray("trackIds") ?: JSONArray()
        val songById = linkedMapOf<String, NeteaseAccountSong>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            parseSong(row)?.let { songById[it.id] = it }
        }
        val orderedIds = mutableListOf<String>()
        for (i in 0 until ids.length()) {
            val sid = ids.optJSONObject(i)?.optString("id").orEmpty()
            if (validId(sid) && sid !in orderedIds) orderedIds.add(sid)
        }
        if (orderedIds.isEmpty()) orderedIds.addAll(songById.keys)
        val visibleIds = orderedIds.take(maxTracks.coerceIn(1, 3000))
        // Detail endpoint may only return a subset of tracks. Fill missing metadata in batches.
        val missing = visibleIds.filter { it !in songById }
        for (batch in missing.chunked(50)) {
            val request = JSONObject().put("ids", JSONArray(batch))
            val response = transport.executeAccount("/api/v3/song/detail", request)
            val songs = response.optJSONArray("songs") ?: JSONArray()
            for (i in 0 until songs.length()) {
                val row = songs.optJSONObject(i) ?: continue
                parseSong(row)?.let { songById[it.id] = it }
            }
        }
        val ordered = visibleIds.mapNotNull(songById::get)
        return NeteaseAccountPlaylistDetail(
            playlistId = id,
            name = playlist.optString("name", "网易云歌单").take(120),
            trackCount = maxOf(playlist.optInt("trackCount", 0), orderedIds.size),
            songs = ordered,
            creatorName = playlist.optJSONObject("creator")?.optString("nickname").orEmpty().take(100),
            description = playlist.optString("description").take(500),
            playCount = playlist.optLong("playCount", 0L).coerceAtLeast(0L)
        )
    }

    /** Highest authorized stereo quality, with actual-response quality checks.
     *  `level` requests are hints. A valid URL may be silently downgraded;
     *  therefore we compare the actual `level`/`type`/`br` from each response.
     *  Dolby/spatial-only variants are excluded because they require special decoders.
     *  URLs are never returned to untrusted JavaScript code.
     */
    suspend fun streamUrl(songId: String): String = kotlinx.coroutines.withTimeout(50_000L) {
        require(validId(songId)) { "歌曲 ID 不合法" }
        val tiers = listOf("jymaster", "hires", "lossless", "exhigh", "higher", "standard")
        val ranks = tiers.reversed().withIndex().associate { it.value to it.index + 1 }
        data class Candidate(val url: String, val rank: Int, val br: Long)
        var best: Candidate? = null
        var lastProblem: String? = null
        for (tier in tiers) {
            val result = try {
                transport.executeAccount(
                    "/api/song/enhance/player/url/v1",
                    JSONObject().put("id", songId).put("level", tier)
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lastProblem = e.localizedMessage.orEmpty().take(140)
                continue
            }
            val data = result.optJSONArray("data")?.optJSONObject(0) ?: continue
            val rawUrl = data.optString("url").takeIf { it.isNotBlank() && it != "null" }
                ?: continue
            val validated = try { checkedStreamUrl(rawUrl) } catch (_: Exception) { continue }
            val actualLevel = data.optString("level").lowercase()
            val br = data.optLong("br", 0L)
            val kind = data.optString("type").lowercase()
            val actualRank = ranks[actualLevel] ?: when {
                kind == "flac" || kind == "alac" -> 4 // lossless, not proven Hi-Res
                br >= 320000L -> 3
                br >= 192000L -> 2
                else -> 1
            }
            val candidate = Candidate(validated, actualRank, br)
            if (best == null || candidate.rank > best.rank ||
                (candidate.rank == best.rank && candidate.br > best.br)) {
                best = candidate
            }
            // Stop only when the server actually delivered at least the
            // requested grade. A downgraded URL must not mask better options.
            if (actualRank >= (ranks[tier] ?: 1)) break
        }
        if (best == null) {
            // Legacy endpoint may be the only supported path for some tracks.
            val old = try {
                transport.executeAccount(
                    "/api/song/enhance/player/url", JSONObject().put("id", songId)
                )
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (e: Exception) {
                lastProblem = e.localizedMessage.orEmpty().take(140)
                null
            }
            val url = old?.optJSONArray("data")?.optJSONObject(0)?.optString("url")
                ?.takeIf { it.isNotBlank() && it != "null" }
            if (url != null) best = Candidate(checkedStreamUrl(url), 1, 0L)
        }
        best?.url ?: error(
            "当前账号无法取得可播放资源（会员/地区/版权限制或接口异常）" +
                lastProblem?.let { "：$it" }.orEmpty()
        )
    }

    private fun checkedStreamUrl(url: String): String {
        val parsed = runCatching { java.net.URI(url) }.getOrNull()
            ?: error("网易云返回了无效播放地址")
        val host = parsed.host?.lowercase() ?: error("播放地址缺少主机名")
        require(host == "music.126.net" || host.endsWith(".music.126.net") ||
            host == "music.163.com" || host.endsWith(".music.163.com")) {
            "播放地址不是允许的网易云音乐 CDN"
        }
        require(parsed.scheme in listOf("https", "http") &&
            parsed.userInfo == null && parsed.port == -1) { "播放地址不安全" }
        return if (parsed.scheme == "http") url.replaceFirst("http://", "https://") else url
    }

    /** Synchronized LRC for the selected track. No cookie or token is returned. */
    suspend fun lyrics(songId: String): String? {
        require(validId(songId)) { "歌曲 ID 不合法" }
        val result = transport.executeAccount(
            "/api/song/lyric/v1", JSONObject().put("id", songId)
        )
        return result.optJSONObject("lrc")?.optString("lyric")
            ?.takeIf { it.isNotBlank() }?.take(1_000_000)
    }

    private fun parseSong(obj: JSONObject): NeteaseAccountSong? {
        val sid = obj.optString("id")
        if (!validId(sid)) return null
        val artists = obj.optJSONArray("ar") ?: obj.optJSONArray("artists") ?: JSONArray()
        val names = (0 until artists.length()).mapNotNull { i ->
            artists.optJSONObject(i)?.optString("name")?.takeIf(String::isNotBlank)
        }
        val album = obj.optJSONObject("al") ?: obj.optJSONObject("album") ?: JSONObject()
        return NeteaseAccountSong(
            id = sid,
            title = obj.optString("name", "未知歌曲").take(150),
            artist = names.joinToString(" / ").ifBlank { "未知歌手" }.take(150),
            album = album.optString("name", "未知专辑").take(100),
            artworkUrl = safeImage(album.optString("picUrl")),
            durationMs = obj.optLong("dt", obj.optLong("duration", 0L)).coerceAtLeast(0L)
        )
    }

    private fun validId(value: String) = value.matches(Regex("[0-9]{1,19}"))
    /** NetEase's image CDN often returns HTTP URLs; upgrade known CDN URLs to HTTPS. */
    private fun safeImage(value: String): String? {
        if (value.length !in 1..1500) return null
        val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "music.126.net" && !host.endsWith(".music.126.net")) return null
        if (uri.scheme !in setOf("http", "https")) return null
        if (uri.userInfo != null || uri.port != -1) return null
        return if (uri.scheme == "http") value.replaceFirst("http://", "https://") else value
    }
}
