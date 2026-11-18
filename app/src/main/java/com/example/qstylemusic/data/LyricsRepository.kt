package com.example.qstylemusic.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.util.Locale

/** Timed lines OR unsynced text; an external selection may override an embedded lyric. */
data class LyricsContent(
    val lines: List<LyricLine>,
    val plain: List<String>,
    val source: String,
    val note: String? = null
) {
    val synced: Boolean get() = lines.isNotEmpty()
    val exists: Boolean get() = synced || plain.isNotEmpty()
}

class LyricsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("song_lyrics_documents", Context.MODE_PRIVATE)

    fun selectedDocument(songId: Long): Uri? = prefs.getString("lrc_$songId", null)
        ?.let(Uri::parse)

    fun setSelectedDocument(songId: Long, uri: Uri) {
        prefs.edit().putString("lrc_$songId", uri.toString()).apply()
    }

    fun clearSelectedDocument(songId: Long) {
        prefs.edit().remove("lrc_$songId").apply()
    }

    // Scoped-storage-safe automatic matching: user grants a Music folder with OpenDocumentTree once.
    fun selectedFolder(): Uri? = prefs.getString("lyrics_folder", null)?.let(Uri::parse)

    fun setSelectedFolder(uri: Uri) {
        prefs.edit().putString("lyrics_folder", uri.toString()).apply()
        cachedFolder = null
        autoIndex.clear()
    }

    private data class IndexedLyrics(val uri: Uri, val documentId: String)
    private var cachedFolder: Uri? = null
    private val autoIndex = mutableMapOf<String, MutableList<IndexedLyrics>>()

    suspend fun load(songId: Long, explicitFile: Uri?): LyricsContent = withContext(Dispatchers.IO) {
        var importNote: String? = null
        if (explicitFile != null) {
            try {
                val externalText = decodeLyricsDocument(readBounded(context, explicitFile))
                val content = fromText(externalText, "手动关联 LRC")
                if (content.exists) return@withContext content
                importNote = "所选歌词文件没有可显示的内容，已尝试其他来源"
            } catch (ex: Exception) {
                importNote = "手动 LRC 读取失败，已尝试其他来源"
            }
        }

        val audioUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
        // A matching sidecar LRC in an SAF-authorized directory is preferred over an embedded lyric.
        val auto = try {
            findSidecar(audioUri)?.let { uri ->
                fromText(decodeLyricsDocument(readBounded(context, uri)), "同名 LRC 文件")
            }
        } catch (_: Exception) { null }
        if (auto?.synced == true) return@withContext auto.copy(note = importNote)

        val extracted = try {
            context.contentResolver.openFileDescriptor(audioUri, "r")?.use { descriptor ->
                // Seekable MediaStore file descriptors allow reading only metadata, not entire audio.
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    EmbeddedLyricsParser.read(stream.channel)
                }
            }
        } catch (_: Exception) { null }
        val embedded = extracted?.let { fromText(it.text, "内嵌歌词 · ${it.format} (${it.field})") }
        // Prefer synchronized content, even if only unsynchronized text is available in the sidecar.
        when {
            embedded?.synced == true -> embedded.copy(note = importNote)
            auto?.exists == true -> auto.copy(note = importNote)
            embedded?.exists == true -> embedded.copy(note = importNote)
            else -> LyricsContent(emptyList(), emptyList(), "无歌词", importNote)
        }
    }

    /** Returns the same-base-name .lrc under the user-chosen SAF music folder, if any. */
    private fun findSidecar(audioUri: Uri): Uri? {
        val filename = context.contentResolver.query(
            audioUri,
            arrayOf(MediaStore.Audio.Media.DISPLAY_NAME),
            null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return null
        val base = filename.substringBeforeLast('.', filename).lowercase(Locale.ROOT)
        if (base.isBlank()) return null
        val folder = selectedFolder() ?: return null
        if (cachedFolder != folder) indexFolder(folder)
        return autoIndex[base]?.firstOrNull()?.uri
    }

    /** Lazy one-time bounded directory scan. No MANAGE_EXTERNAL_STORAGE needed. */
    private fun indexFolder(folder: Uri) {
        cachedFolder = folder
        autoIndex.clear()
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(folder)
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(rootId to 0)
        var examined = 0
        while (queue.isNotEmpty() && examined < 12_000) {
            val (parentId, depth) = queue.removeFirst()
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(folder, parentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            try {
                resolver.query(uri, columns, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext() && examined++ < 12_000) {
                        val id = cursor.getString(0) ?: continue
                        val name = cursor.getString(1).orEmpty()
                        val mime = cursor.getString(2).orEmpty()
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR && depth < 7) {
                            queue.add(id to (depth + 1))
                        } else if (name.endsWith(".lrc", ignoreCase = true)) {
                            val base = name.dropLast(4).lowercase(Locale.ROOT)
                            val file = DocumentsContract.buildDocumentUriUsingTree(folder, id)
                            autoIndex.getOrPut(base) { mutableListOf() }.add(IndexedLyrics(file, id))
                        }
                    }
                }
            } catch (_: Exception) {
                // A provider might not expose all children; other folders are still usable.
            }
        }
    }

    private fun fromText(raw: String, source: String): LyricsContent {
        val parsed = LrcParser.parse(raw)
        if (parsed.isNotEmpty()) return LyricsContent(parsed, emptyList(), source)
        return LyricsContent(emptyList(), LrcParser.plainLines(raw), source)
    }
}

/** Works with Android Storage Access Framework and does not require storage-all-files permission. */
private fun readBounded(context: Context, uri: Uri): ByteArray {
    val output = ByteArrayOutputStream()
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (output.size() + n > 2 * 1024 * 1024) {
                throw IllegalArgumentException("LRC 文件超过 2 MB")
            }
            output.write(buffer, 0, n)
        }
    } ?: throw IllegalStateException("不能读取所选文件")
    return output.toByteArray()
}

private fun decodeLyricsDocument(data: ByteArray): String {
    if (data.size >= 3 && data.sliceArray(0..2).contentEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))) {
        return String(data, 3, data.size - 3, Charsets.UTF_8)
    }
    if (data.size >= 2 && data[0] == 0xff.toByte() && data[1] == 0xfe.toByte()) {
        return String(data, 2, data.size - 2, Charsets.UTF_16LE)
    }
    if (data.size >= 2 && data[0] == 0xfe.toByte() && data[1] == 0xff.toByte()) {
        return String(data, 2, data.size - 2, Charsets.UTF_16BE)
    }
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data)).toString()
    } catch (_: CharacterCodingException) {
        // Many Chinese LRC documents still use GB18030 rather than UTF-8.
        String(data, charset("GB18030"))
    }
}
