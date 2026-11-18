package com.example.qstylemusic.plugin

/** Metadata and a currently usable, authorized stream returned by the source plugin. */
data class OnlinePlayableTrack(
    val mediaId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val streamUrl: String,
    val lyrics: String?
)
