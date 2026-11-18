package com.example.qstylemusic.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans Android's indexed local music, honoring the user's included/excluded folders.
 * It never walks arbitrary filesystem directories or needs MANAGE_EXTERNAL_STORAGE.
 */
class MusicScanner(private val context: Context) {
    suspend fun querySongs(): List<Song> = withContext(Dispatchers.IO) {
        // Read the latest persisted rules for every rescan (no ViewModel restart required).
        val rules = LocalScanSettingsStore(context).load()
        if (rules.mode == LocalScanMode.SELECTED_ONLY && rules.included.isEmpty()) {
            return@withContext emptyList()
        }

        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.MediaColumns.RELATIVE_PATH
            projection += MediaStore.MediaColumns.VOLUME_NAME
        } else {
            @Suppress("DEPRECATION")
            val oldData = MediaStore.MediaColumns.DATA
            projection += oldData
        }

        val baseSelection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sorting = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        // On Android 10+, apply the selected folders directly in MediaStore's SQL
        // query wherever supported. Folder names are bound as arguments; no unsafe
        // path strings are concatenated into the SQL.
        val filteredFolders = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            rules.activeFolders else emptyList()
        val clauses = filteredFolders.joinToString(" OR ") { folder ->
            // Some providers report the primary volume as "external" rather than
            // "external_primary" in their aggregate queries.
            val volumeTest = if (folder.volumeName == "external_primary") {
                "COALESCE(${MediaStore.MediaColumns.VOLUME_NAME}, '') IN (?, ?)"
            } else {
                "COALESCE(${MediaStore.MediaColumns.VOLUME_NAME}, '') = ?"
            }
            "($volumeTest AND " +
                "COALESCE(${MediaStore.MediaColumns.RELATIVE_PATH}, '') LIKE ? ESCAPE '!')"
        }
        val scopedSelection = if (clauses.isNotBlank()) {
            if (rules.mode == LocalScanMode.SELECTED_ONLY)
                "$baseSelection AND ($clauses)"
            else "$baseSelection AND NOT ($clauses)"
        } else baseSelection
        val scopedArgs: Array<String>? = if (clauses.isNotBlank()) {
            filteredFolders.flatMap {
                if (it.volumeName == "external_primary")
                    listOf("external_primary", "external", escapeForLike(it.relativePath))
                else listOf(it.volumeName, escapeForLike(it.relativePath))
            }.toTypedArray()
        } else null

        // Some OEM MediaProviders don't accept COALESCE/LIKE/ESCAPE.
        // Fall back to normal MediaStore querying, STILL applying exactly the same
        // in-memory folder filter. Never return unrelated tracks as a fallback.
        val cursor: Cursor? = try {
            context.contentResolver.query(
                uri, projection.toTypedArray(), scopedSelection, scopedArgs, sorting
            )
        } catch (failure: RuntimeException) {
            if (clauses.isBlank()) throw failure
            context.contentResolver.query(
                uri, projection.toTypedArray(), baseSelection, null, sorting
            )
        }

        val songs = mutableListOf<Song>()
        cursor?.use { c ->
            val idIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val relativeIx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
            val volumeIx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                c.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME) else -1
            @Suppress("DEPRECATION")
            val legacyIx = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
                c.getColumnIndex(MediaStore.MediaColumns.DATA) else -1

            while (c.moveToNext()) {
                val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val volume = if (volumeIx >= 0) c.getString(volumeIx).orEmpty() else ""
                    val relative = if (relativeIx >= 0) c.getString(relativeIx).orEmpty() else ""
                    volume to relative
                } else {
                    legacyFolder(if (legacyIx >= 0) c.getString(legacyIx).orEmpty() else "")
                        ?: ("" to "")
                }
                if (!rules.matches(folder.first, folder.second)) continue

                val id = c.getLong(idIx)
                val title = c.getString(titleIx).orEmpty().takeUnless { it == "<unknown>" }.orEmpty()
                val artist = c.getString(artistIx).orEmpty().takeUnless { it == "<unknown>" }.orEmpty()
                val album = c.getString(albumIx).orEmpty().takeUnless { it == "<unknown>" }.orEmpty()
                songs += Song(
                    id = id,
                    title = title.ifBlank { "未知歌曲" },
                    artist = artist.ifBlank { "未知艺术家" },
                    album = album.ifBlank { "未知专辑" },
                    albumId = c.getLong(albumIdIx),
                    durationMs = c.getLong(durationIx).coerceAtLeast(0L),
                    uri = ContentUris.withAppendedId(uri, id)
                )
            }
        }
        songs
    }

    /** Android 8/9 fallback, where MediaStore has no RELATIVE_PATH/VOLUME_NAME. */
    private fun legacyFolder(absolutePath: String): Pair<String, String>? {
        val path = absolutePath.replace('\\', '/')
        return when {
            path.startsWith("/storage/emulated/") -> {
                val withoutRoot = path.removePrefix("/storage/emulated/")
                val relativeFile = withoutRoot.substringAfter('/', "")
                if (relativeFile.isBlank()) null else
                    "external_primary" to relativeFile.substringBeforeLast('/', "")
            }
            path.startsWith("/sdcard/") ->
                "external_primary" to path.removePrefix("/sdcard/").substringBeforeLast('/', "")
            path.startsWith("/storage/") -> {
                val remainder = path.removePrefix("/storage/")
                val volume = remainder.substringBefore('/', "")
                val relativeFile = remainder.substringAfter('/', "")
                if (volume.isBlank() || relativeFile.isBlank()) null else
                    volume to relativeFile.substringBeforeLast('/', "")
            }
            else -> null
        }
    }
}
