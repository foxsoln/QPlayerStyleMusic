package com.example.qstylemusic.data

import android.net.Uri

/** Local MediaStore audio entry. No broad filesystem paths are needed. */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val uri: Uri
) {
    // Album artwork from MediaStore is best-effort: some devices don't expose it.
    val artworkUri: Uri?
        get() = if (albumId > 0L) Uri.parse("content://media/external/audio/albumart/$albumId") else null
}

fun formatDuration(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) / 1000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
