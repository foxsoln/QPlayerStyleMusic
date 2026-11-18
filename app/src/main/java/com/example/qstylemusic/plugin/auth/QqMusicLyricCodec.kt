package com.example.qstylemusic.plugin.auth

import java.util.Base64

/**
 * Normalizes the plain or base64-encoded line-synchronized lyrics returned by
 * QQ's legacy Web lyric service. Encrypted QRC is not interpreted as plain LRC.
 * Pure Kotlin to enable JVM tests without Android.
 */
object QqMusicLyricCodec {
    private val timeTag = Regex("\\[(?:[0-9]{1,3}):[0-5][0-9](?:[.:][0-9]{1,3})?]")
    private val base64Text = Regex("^[A-Za-z0-9+/]+={0,2}$")

    fun hasTimedLines(value: String?): Boolean =
        value != null && timeTag.containsMatchIn(value)

    fun normalize(source: String?): String? {
        val raw = source?.trim()?.removePrefix("\uFEFF")?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > 600_000) return null
        if (hasTimedLines(raw)) return normalizeNewlines(raw)
        val encoded = raw.filterNot(Char::isWhitespace)
        if (encoded.length % 4 == 1 || !base64Text.matches(encoded)) return null
        val decoded = try {
            val bytes = Base64.getDecoder().decode(encoded)
            if (bytes.size > 400_000) return null
            String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").trim()
        } catch (_: IllegalArgumentException) {
            return null
        }
        return if (hasTimedLines(decoded)) normalizeNewlines(decoded) else null
    }

    private fun normalizeNewlines(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n').trim()
}
