package com.example.qstylemusic.data

/** Synchronized lyrics at millisecond precision. */
data class LyricLine(val timeMs: Long, val text: String)

/** Standard LRC, multi-time-tag LRC, [offset:], and enhanced LRC word tags (line display). */
object LrcParser {
    private val timeTag = Regex("""\[(\d{1,4}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val enhancedWord = Regex("""<\d{1,4}:\d{2}(?:[.:]\d{1,3})?>""")
    private val offsetTag = Regex("""(?im)^\s*\[offset:\s*([+-]?\d+)\s*]""")
    private val metaTag = Regex("""(?im)^\s*\[(?:ar|ti|al|by|length|re|ve|au):[^]]*]""")

    fun parse(raw: String): List<LyricLine> {
        val source = raw.removePrefix("\uFEFF")
        val offset = offsetTag.find(source)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        val out = mutableListOf<LyricLine>()
        source.lineSequence().forEach { line ->
            val tags = timeTag.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            val text = timeTag.replace(line, "").replace(enhancedWord, "").trim()
            if (text.isBlank()) return@forEach
            for (tag in tags) {
                val min = tag.groupValues[1].toLongOrNull() ?: continue
                val sec = tag.groupValues[2].toLongOrNull() ?: continue
                if (sec >= 60L) continue
                val fraction = tag.groupValues[3].padEnd(3, '0').toLongOrNull() ?: 0L
                val time = (min * 60_000 + sec * 1_000 + fraction + offset).coerceAtLeast(0L)
                out += LyricLine(time, text)
            }
        }
        return out.sortedBy { it.timeMs }.distinctBy { it.timeMs to it.text }
    }

    fun plainLines(raw: String): List<String> = raw.removePrefix("\uFEFF")
        .lineSequence().map(String::trim)
        .filter { it.isNotEmpty() && !metaTag.matches(it) && !offsetTag.matches(it) }
        .take(350).toList()
}
