package com.example.qstylemusic.data

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/** Bounded, read-only tag parser for common local audio formats. */
data class EmbeddedLyrics(val text: String, val format: String, val field: String)

object EmbeddedLyricsParser {
    private const val LIMIT = 4 * 1024 * 1024
    private val LATIN = Charsets.ISO_8859_1

    fun read(channel: FileChannel): EmbeddedLyrics? {
        val r = Access(channel)
        val head = r.at(0, 12)
        var skip = 0L
        val id3 = if (head.has("ID3")) {
            skip = syncSafe(head, 6).toLong() + 10L
            readId3(r, 0, "MP3/ID3")
        } else null
        val prefix = r.at(skip, 12)
        val native = when {
            prefix.has("fLaC") -> flac(r, skip)
            prefix.has("OggS") -> ogg(r, skip)
            prefix.size >= 8 && prefix.copyOfRange(4, 8).has("ftyp") -> mp4(r, skip)
            prefix.has("RIFF") || prefix.has("RF64") -> wav(r, skip)
            else -> null
        }
        return best(listOf(id3, native, ape(r)))
    }

    private fun best(items: List<EmbeddedLyrics?>): EmbeddedLyrics? = items.filterNotNull()
        .filter { it.text.isNotBlank() }.maxByOrNull {
            (if (LrcParser.parse(it.text).isNotEmpty()) 1000 else 0) +
                (if (it.field.contains("SYNC", true) || it.field.contains("SYLT", true)) 10 else 0)
        }

    // MP3, AAC-with-ID3, or RIFF/WAV ID3 chunk. Handles USLT, SYLT, TXXX, and v2.2 equivalents.
    private fun readId3(r: Access, start: Long, format: String): EmbeddedLyrics? {
        val h = r.at(start, 10)
        if (h.size != 10 || !h.has("ID3") || h[3].u() !in 2..4) return null
        val version = h[3].u()
        val len = syncSafe(h, 6)
        if (len <= 0 || len > LIMIT) return null
        val raw = r.at(start + 10, len)
        if (raw.size != len) return null
        val bytes = if (version < 4 && h[5].u() and 0x80 != 0) unescape(raw) else raw
        var p = 0
        if (version >= 3 && h[5].u() and 0x40 != 0) {
            p = if (version == 3) ((big32(bytes, 0) ?: return null) + 4).toInt() else syncSafe(bytes, 0)
        }
        val found = mutableListOf<EmbeddedLyrics>()
        val frameHead = if (version == 2) 6 else 10
        while (p >= 0 && p + frameHead <= bytes.size) {
            val tagLen = if (version == 2) 3 else 4
            val type = bytes.copyOfRange(p, p + tagLen).toString(LATIN)
            if (!type.all { it.isLetterOrDigit() }) break
            val size = when (version) {
                2 -> (bytes[p + 3].u() shl 16) or (bytes[p + 4].u() shl 8) or bytes[p + 5].u()
                4 -> syncSafe(bytes, p + 4)
                else -> big32(bytes, p + 4)?.toInt() ?: break
            }
            if (size <= 0 || size > LIMIT || p + frameHead + size > bytes.size) break
            val frameRaw = bytes.copyOfRange(p + frameHead, p + frameHead + size)
            val frame = if (version == 4 && bytes[p + 9].u() and 0x02 != 0) unescape(frameRaw) else frameRaw
            val decoded = when (type) {
                "USLT", "ULT" -> uslt(frame)
                "SYLT", "SLT" -> sylt(frame)
                "TXXX", "TXX" -> txxx(frame)
                else -> null
            }
            if (!decoded.isNullOrBlank()) found += EmbeddedLyrics(decoded.trim(), format, type)
            p += frameHead + size
        }
        return best(found)
    }

    private fun uslt(b: ByteArray): String? {
        if (b.size < 5) return null
        val pos = endOfString(b, 4, b[0].u()) ?: return null
        return string(b, pos, b.size, b[0].u()).takeIf { it.isNotBlank() }
    }

    private fun txxx(b: ByteArray): String? {
        if (b.size < 3) return null
        val enc = b[0].u()
        val split = startOfTerminator(b, 1, enc) ?: return null
        val name = string(b, 1, split, enc).uppercase()
        if (listOf("LYRIC", "LRC", "KARAOKE").none { name.contains(it) }) return null
        val pos = split + if (enc in 1..2) 2 else 1
        return string(b, pos, b.size, enc).takeIf { it.isNotBlank() }
    }

    private fun sylt(b: ByteArray): String? {
        if (b.size < 12 || b[4].u() != 2) return null  // timestamp unit must be milliseconds
        val enc = b[0].u()
        var p = endOfString(b, 6, enc) ?: return null
        val out = mutableListOf<String>()
        repeat(600) {
            val end = startOfTerminator(b, p, enc) ?: return@repeat
            val next = end + if (enc in 1..2) 2 else 1
            val ms = big32(b, next) ?: return@repeat
            val value = string(b, p, end, enc)
            if (value.isNotBlank()) {
                val m = (ms / 60_000L).toString().padStart(2, '0')
                val s = ((ms / 1000L) % 60).toString().padStart(2, '0')
                val cs = ((ms % 1000L) / 10L).toString().padStart(2, '0')
                out += "[$m:$s.$cs]$value"
            }
            p = next + 4
        }
        return out.joinToString("\n").takeIf { it.isNotEmpty() }
    }

    // FLAC metadata blocks, Ogg/Opus comments, and Vorbis-style fields.
    private fun flac(r: Access, start: Long): EmbeddedLyrics? {
        var cursor = start + 4
        for (block in 0 until 80) {
            val h = r.at(cursor, 4)
            if (h.size < 4) break
            val last = h[0].u() and 0x80 != 0
            val type = h[0].u() and 0x7F
            val len = (h[1].u() shl 16) or (h[2].u() shl 8) or h[3].u()
            if (type == 4 && len in 1..LIMIT) {
                val embedded = fromComments(comments(r.at(cursor + 4, len)), "FLAC")
                if (embedded != null) return embedded
            }
            cursor += len.toLong() + 4
            if (last || cursor >= r.size) break
        }
        return null
    }

    private fun ogg(r: Access, start: Long): EmbeddedLyrics? {
        var pos = start
        val packet = java.io.ByteArrayOutputStream()
        var total = 0
        for (page in 0 until 80) {
            val h = r.at(pos, 27)
            if (h.size < 27 || !h.has("OggS")) break
            val table = r.at(pos + 27, h[26].u())
            if (table.size != h[26].u()) break
            var dataAt = pos + 27 + table.size
            for (seg in table) {
                val n = seg.u()
                if (packet.size() + n > LIMIT) return null
                val bytes = r.at(dataAt, n)
                if (bytes.size != n) return null
                packet.write(bytes)
                dataAt += n.toLong()
                total += n
                if (n < 255) {
                    val bytesAll = packet.toByteArray()
                    val skip = when {
                        bytesAll.has("OpusTags") -> 8
                        bytesAll.size >= 7 && bytesAll[0].u() == 3 &&
                            bytesAll.copyOfRange(1, 7).has("vorbis") -> 7
                        else -> 0
                    }
                    if (skip > 0) return fromComments(
                        comments(bytesAll.copyOfRange(skip, bytesAll.size)), "Ogg/Opus"
                    )
                    packet.reset()
                }
            }
            pos = dataAt
            if (total > LIMIT || pos >= r.size) break
        }
        return null
    }

    private fun comments(data: ByteArray): Map<String, String> {
        var p = 0
        fun length(): Int? {
            val value = little32(data, p) ?: return null
            if (value > LIMIT) return null
            p += 4
            return value.toInt()
        }
        val vendor = length() ?: return emptyMap()
        if (p + vendor + 4 > data.size) return emptyMap()
        p += vendor
        val count = length() ?: return emptyMap()
        if (count > 2000) return emptyMap()
        val out = linkedMapOf<String, String>()
        repeat(count) {
            val size = length() ?: return out
            if (size < 0 || p + size > data.size) return out
            val entry = String(data, p, size, Charsets.UTF_8)
            p += size
            val equal = entry.indexOf('=')
            if (equal > 0) {
                val key = entry.substring(0, equal).uppercase().trim()
                if (key in lyricKeys) out[key] = entry.substring(equal + 1).take(LIMIT)
            }
        }
        return out
    }

    private val lyricKeys = setOf("SYNCEDLYRICS", "LRC", "LYRICS", "UNSYNCEDLYRICS", "LYRIC", "KARAOKE")
    private fun fromComments(map: Map<String, String>, format: String): EmbeddedLyrics? =
        best(lyricKeys.mapNotNull { field -> map[field]?.let { EmbeddedLyrics(it, format, field) } })

    // MP4-family metadata: examine moov/udta/meta/ilst without reading mdat audio payload.
    private fun mp4(r: Access, start: Long): EmbeddedLyrics? {
        val found = mutableListOf<EmbeddedLyrics>()
        fun atoms(from: Long, to: Long, depth: Int) {
            if (depth > 9 || from >= to) return
            var p = from
            var visited = 0
            while (p + 8 <= to && visited++ < 1500) {
                val header = r.at(p, 16)
                if (header.size < 8) break
                val size32 = big32(header, 0) ?: break
                val sizeHeader = if (size32 == 1L) 16 else 8
                val size = when (size32) {
                    0L -> to - p
                    1L -> big64(header, 8) ?: break
                    else -> size32
                }
                if (size < sizeHeader || size > to - p) break
                val type = header.copyOfRange(4, 8)
                val name = type.toString(LATIN)
                val body = p + sizeHeader
                val end = p + size
                when {
                    type.contentEquals(byteArrayOf(0xa9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())) ->
                        found += ilstLyrics(r, body, end, "©lyr")
                    name == "----" -> freeform(r, body, end)?.let(found::add)
                    name in setOf("moov", "udta", "meta", "ilst") ->
                        atoms(body + if (name == "meta") 4 else 0, end, depth + 1)
                }
                p = end
            }
        }
        atoms(start, r.size, 0)
        return best(found)
    }

    private fun ilstLyrics(r: Access, from: Long, to: Long, name: String): List<EmbeddedLyrics> {
        var p = from
        val result = mutableListOf<EmbeddedLyrics>()
        while (p + 16 <= to) {
            val h = r.at(p, 16)
            val len = big32(h, 0) ?: break
            if (len < 16 || len > to - p) break
            if (h.copyOfRange(4, 8).has("data") && len - 16 <= LIMIT) {
                val value = r.at(p + 16, (len - 16).toInt()).toString(Charsets.UTF_8)
                    .trim('\u0000', '\uFEFF', ' ', '\r', '\n')
                if (value.isNotBlank()) result += EmbeddedLyrics(value, "M4A/MP4", name)
            }
            p += len
        }
        return result
    }

    private fun freeform(r: Access, from: Long, to: Long): EmbeddedLyrics? {
        var p = from
        var key = ""
        var text = ""
        while (p + 12 <= to) {
            val head = r.at(p, 16)
            val len = big32(head, 0) ?: break
            if (len < 12 || len > to - p || len > LIMIT) break
            val kind = head.copyOfRange(4, 8).toString(LATIN)
            if (kind == "name" && len > 12) key = r.at(p + 12, (len - 12).toInt()).toString(Charsets.UTF_8)
            if (kind == "data" && len > 16) text = r.at(p + 16, (len - 16).toInt()).toString(Charsets.UTF_8)
            p += len
        }
        return if ((key.contains("lyrics", true) || key.contains("lrc", true)) && text.isNotBlank())
            EmbeddedLyrics(text.trim(), "M4A/MP4", key) else null
    }

    // RIFF WAV commonly stores ID3v2 lyrics in an "id3 " chunk.
    private fun wav(r: Access, start: Long): EmbeddedLyrics? {
        if (!r.at(start + 8, 4).has("WAVE")) return null
        var p = start + 12
        var steps = 0
        while (p + 8 <= r.size && steps++ < 100_000) {
            val h = r.at(p, 8)
            if (h.size < 8) break
            val kind = h.copyOfRange(0, 4).toString(LATIN)
            val len = little32(h, 4) ?: break
            if (len == 0xffffffffL) break // RF64 ds64 oversized chunks not supported in V1
            if (kind.equals("ID3 ", true)) return readId3(r, p + 8, "WAV/ID3")
            val next = p + 8 + len + (len and 1L)
            if (next <= p) break
            p = next
        }
        return null
    }

    // APEv2 footer (if present) contains text-tag items on some MP3/APE/WavPack files.
    private fun ape(r: Access): EmbeddedLyrics? {
        val end = r.size - if (r.size >= 128 && r.at(r.size - 128, 3).has("TAG")) 128 else 0
        val h = r.at(end - 32, 32)
        if (h.size != 32 || !h.has("APETAGEX")) return null
        val size = little32(h, 12) ?: return null
        val count = little32(h, 16) ?: return null
        if (size !in 32..LIMIT.toLong() || count > 300) return null
        val body = r.at(end - size, (size - 32).toInt())
        var p = 0
        val result = mutableListOf<EmbeddedLyrics>()
        for (i in 0 until count.toInt()) {
            if (p + 9 > body.size) break
            val len = little32(body, p)?.toInt() ?: break
            val flags = little32(body, p + 4) ?: break
            val kEnd = body.findZero(p + 8, 128) ?: break
            val field = body.copyOfRange(p + 8, kEnd).toString(LATIN).uppercase()
            val from = kEnd + 1
            if (len < 0 || len > LIMIT || len > body.size - from) break
            if (flags and 6L == 0L && listOf("LYRIC", "LRC", "KARAOKE").any { field.contains(it) }) {
                result += EmbeddedLyrics(body.copyOfRange(from, from + len).toString(Charsets.UTF_8), "APEv2", field)
            }
            p = from + len
        }
        return best(result)
    }

    private class Access(private val channel: FileChannel) {
        val size: Long get() = channel.size()
        fun at(start: Long, len: Int): ByteArray {
            if (len <= 0 || len > LIMIT || start < 0 || start >= size) return byteArrayOf()
            val available = minOf(len.toLong(), size - start).toInt()
            val buffer = ByteBuffer.allocate(available)
            var position = start
            while (buffer.hasRemaining()) {
                val consumed = channel.read(buffer, position)
                if (consumed <= 0) break
                position += consumed
            }
            return buffer.array().copyOf(buffer.position())
        }
    }

    private fun Byte.u() = toInt() and 0xff
    private fun ByteArray.has(prefix: String): Boolean =
        size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }
    private fun ByteArray.findZero(from: Int, maximum: Int): Int? {
        for (i in from until minOf(size, from + maximum)) if (this[i] == 0.toByte()) return i
        return null
    }
    private fun syncSafe(bytes: ByteArray, pos: Int): Int {
        if (pos < 0 || pos + 4 > bytes.size) return 0
        return (bytes[pos].u() and 127 shl 21) or (bytes[pos + 1].u() and 127 shl 14) or
            (bytes[pos + 2].u() and 127 shl 7) or (bytes[pos + 3].u() and 127)
    }
    private fun little32(b: ByteArray, p: Int): Long? = if (p < 0 || p + 4 > b.size) null else
        b[p].u().toLong() or (b[p + 1].u().toLong() shl 8) or
            (b[p + 2].u().toLong() shl 16) or (b[p + 3].u().toLong() shl 24)
    private fun big32(b: ByteArray, p: Int): Long? = if (p < 0 || p + 4 > b.size) null else
        (b[p].u().toLong() shl 24) or (b[p + 1].u().toLong() shl 16) or
            (b[p + 2].u().toLong() shl 8) or b[p + 3].u().toLong()
    private fun big64(b: ByteArray, p: Int): Long? {
        if (p < 0 || p + 8 > b.size) return null
        var result = 0L
        for (i in p until p + 8) result = (result shl 8) or b[i].u().toLong()
        return result.takeIf { it >= 0 }
    }
    private fun startOfTerminator(b: ByteArray, from: Int, encoding: Int): Int? {
        val step = if (encoding == 1 || encoding == 2) 2 else 1
        var p = from
        while (p + step <= b.size) {
            if (step == 1 && b[p] == 0.toByte()) return p
            if (step == 2 && b[p] == 0.toByte() && b[p + 1] == 0.toByte()) return p
            p += step
        }
        return null
    }
    private fun endOfString(b: ByteArray, from: Int, encoding: Int): Int? =
        startOfTerminator(b, from, encoding)?.plus(if (encoding == 1 || encoding == 2) 2 else 1)
    private fun string(b: ByteArray, from: Int, to: Int, encoding: Int): String {
        if (from < 0 || to > b.size || from >= to || to - from > LIMIT) return ""
        val charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charset.forName("UTF-16BE")
            3 -> Charsets.UTF_8
            else -> LATIN
        }
        return b.copyOfRange(from, to).toString(charset).trim('\u0000', '\uFEFF')
    }
    private fun unescape(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var p = 0
        while (p < bytes.size) {
            out.write(bytes[p].u())
            if (bytes[p].u() == 255 && p + 1 < bytes.size && bytes[p + 1] == 0.toByte()) p++
            p++
        }
        return out.toByteArray()
    }
}
