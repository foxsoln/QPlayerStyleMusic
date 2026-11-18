package com.example.qstylemusic.playback

import android.content.Context
import com.example.qstylemusic.plugin.OnlinePlayableTrack
import com.example.qstylemusic.plugin.auth.NeteaseAccountClient
import com.example.qstylemusic.plugin.auth.QqMusicAccountClient
import com.example.qstylemusic.plugin.auth.QqMusicLyricClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Favorites for online tracks. Stored by stable source-qualified mediaId, never by
 * a local MediaStore Long id. No URLs, login cookies, keys or stream tickets are saved.
 * This is an APP-LOCAL collection; it does not write back to QQ/NetEase cloud likes.
 */
data class OnlineFavorite(
    val mediaId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?
)

class OnlineFavoritesStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("qstyle_online_favorites_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<OnlineFavorite> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        val array = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return (0 until minOf(1000, array.length())).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("id")
            if (!validMediaId(id)) return@mapNotNull null
            OnlineFavorite(
                mediaId = id,
                title = obj.optString("title").take(200),
                artist = obj.optString("artist").take(200),
                artworkUrl = obj.optString("artwork").takeIf { it.startsWith("https://") }
            )
        }.distinctBy { it.mediaId }
    }

    @Synchronized
    fun toggle(item: OnlineFavorite): List<OnlineFavorite> {
        require(validMediaId(item.mediaId)) { "在线音乐标识无效" }
        val current = load().toMutableList()
        val index = current.indexOfFirst { it.mediaId == item.mediaId }
        if (index >= 0) current.removeAt(index) else current.add(0, item)
        val result = current.take(1000)
        val serialized = JSONArray()
        result.forEach { favorite ->
            serialized.put(JSONObject()
                .put("id", favorite.mediaId)
                .put("title", favorite.title.take(200))
                .put("artist", favorite.artist.take(200))
                .put("artwork", favorite.artworkUrl?.take(1600).orEmpty()))
        }
        check(prefs.edit().putString("items", serialized.toString()).commit()) {
            "无法保存在线音乐收藏"
        }
        return result
    }

    private fun validMediaId(value: String): Boolean = value.length in 1..220 &&
        !value.contains('/') && !value.contains('?') && !value.contains('#') &&
        value.matches(Regex("[A-Za-z0-9._:-]+"))
}

/** Re-resolve an authorized URL when the user opens an online song from local favorites. */
class OnlineFavoriteResolver(context: Context) {
    private val qq = QqMusicAccountClient(context.applicationContext)
    private val qqLyric = QqMusicLyricClient(context.applicationContext)
    private val netease = NeteaseAccountClient(context.applicationContext)

    suspend fun resolve(item: OnlineFavorite): OnlinePlayableTrack {
        val id = item.mediaId
        val parts = id.split(':')
        val key = parts.lastOrNull().orEmpty()
        val stream: String
        val lyric: String?
        when {
            id.startsWith("qqmusic:song:") -> {
                require(key.matches(Regex("[A-Za-z0-9]{6,32}"))) { "QQ 音乐歌曲标识无效" }
                stream = qq.streamUrl(key)
                lyric = try {
                    withTimeoutOrNull(5_000L) { qqLyric.lyrics(key).lrc }
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { null }
            }
            parts.firstOrNull()?.lowercase() in setOf("netease", "music163", "neteasemusic") -> {
                require(key.matches(Regex("[0-9]{1,19}"))) { "网易云歌曲标识无效" }
                stream = netease.streamUrl(key)
                lyric = try {
                    withTimeoutOrNull(5_000L) { netease.lyrics(key) }
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) { null }
            }
            else -> error("此音源暂不支持从本机收藏重新获取播放地址，请从原歌单播放")
        }
        return OnlinePlayableTrack(
            mediaId = item.mediaId,
            title = item.title,
            artist = item.artist,
            artworkUrl = item.artworkUrl,
            streamUrl = stream,
            lyrics = lyric
        )
    }
}
