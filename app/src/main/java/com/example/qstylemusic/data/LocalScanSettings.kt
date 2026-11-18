package com.example.qstylemusic.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Local library scanning rules. No raw filesystem access is needed. */
enum class LocalScanMode {
    /** Index only audio under one or more selected directories. */
    SELECTED_ONLY,
    /** Index all MediaStore music except the selected directories. */
    EXCLUDE_SELECTED
}

/** A directory relative to a MediaStore volume, including all its subdirectories. */
data class LocalScanFolder(val volumeName: String, val relativePath: String) {
    val displayName: String
        get() = (if (volumeName == "external_primary") "内部存储" else "存储卷 $volumeName") +
            " / " + relativePath.trimEnd('/')

    /** Prefix comparisons are folder-boundary aware: Music/A won't match Music/ABC. */
    fun contains(volume: String, relative: String): Boolean =
        normalizeVolume(volume) == normalizeVolume(volumeName) &&
            normalizeDirectory(relative).startsWith(relativePath, ignoreCase = true)

    companion object {
        fun create(volume: String, path: String): LocalScanFolder? {
            val vol = normalizeVolume(volume)
            val cleaned = path.trim().replace('\\', '/').trim('/')
                .split('/').filter { it.isNotBlank() }
            if (vol.isBlank() || cleaned.isEmpty() || cleaned.any { it == "." || it == ".." || ':' in it }) {
                return null
            }
            return LocalScanFolder(vol, cleaned.joinToString("/") + "/")
        }
    }
}

/** In-app settings are deliberately independent of the NetEase login and plugin preferences. */
data class LocalScanSettings(
    val mode: LocalScanMode = LocalScanMode.EXCLUDE_SELECTED,
    val included: List<LocalScanFolder> = emptyList(),
    val excluded: List<LocalScanFolder> = emptyList()
) {
    val activeFolders: List<LocalScanFolder>
        get() = if (mode == LocalScanMode.SELECTED_ONLY) included else excluded

    fun matches(volume: String, relative: String): Boolean = when (mode) {
        LocalScanMode.SELECTED_ONLY -> included.any { it.contains(volume, relative) }
        LocalScanMode.EXCLUDE_SELECTED -> excluded.none { it.contains(volume, relative) }
    }

    fun withFolder(folder: LocalScanFolder): LocalScanSettings {
        fun List<LocalScanFolder>.withUnique(item: LocalScanFolder): List<LocalScanFolder> =
            (this + item).distinctBy {
                "${it.volumeName.lowercase(Locale.ROOT)}:${it.relativePath.lowercase(Locale.ROOT)}"
            }
        return if (mode == LocalScanMode.SELECTED_ONLY) {
            copy(included = included.withUnique(folder))
        } else {
            copy(excluded = excluded.withUnique(folder))
        }
    }

    fun withoutFolder(folder: LocalScanFolder): LocalScanSettings =
        if (mode == LocalScanMode.SELECTED_ONLY) copy(included = included - folder)
        else copy(excluded = excluded - folder)

    fun clearActive(): LocalScanSettings =
        if (mode == LocalScanMode.SELECTED_ONLY) copy(included = emptyList())
        else copy(excluded = emptyList())
}

/** Convert Android SAF folder selections to MediaStore volume + relative folder paths. */
object LocalScanFolderParser {
    fun fromTreeUri(uri: Uri): LocalScanFolder? {
        // MediaStore's relative path and volume name can be mapped reliably only
        // for the standard Android external-storage document provider.
        if (uri.authority != "com.android.externalstorage.documents") return null
        return try {
            fromDocumentId(DocumentsContract.getTreeDocumentId(uri))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun fromDocumentId(id: String): LocalScanFolder? {
        val colon = id.indexOf(':')
        if (colon <= 0 || colon >= id.length - 1) return null
        return LocalScanFolder.create(id.substring(0, colon), id.substring(colon + 1))
    }

    /** e.g. Music/Rock, Download, or 1234-5678:Music/Album on an SD card. */
    fun fromManualPath(input: String): LocalScanFolder? {
        val value = input.trim()
        if (value.startsWith('/')) return null
        return if (':' in value) fromDocumentId(value)
        else LocalScanFolder.create("external_primary", value)
    }
}

class LocalScanSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("local_scan_scope", Context.MODE_PRIVATE)

    fun load(): LocalScanSettings {
        val raw = prefs.getString("rules_v1", null) ?: return LocalScanSettings()
        return try {
            val obj = JSONObject(raw)
            val mode = runCatching { LocalScanMode.valueOf(obj.optString("mode")) }
                .getOrDefault(LocalScanMode.EXCLUDE_SELECTED)
            LocalScanSettings(
                mode = mode,
                included = parseFolders(obj.optJSONArray("included")),
                excluded = parseFolders(obj.optJSONArray("excluded"))
            )
        } catch (_: Exception) {
            LocalScanSettings()
        }
    }

    fun save(settings: LocalScanSettings) {
        val obj = JSONObject()
            .put("mode", settings.mode.name)
            .put("included", encodeFolders(settings.included))
            .put("excluded", encodeFolders(settings.excluded))
        prefs.edit().putString("rules_v1", obj.toString()).apply()
    }

    private fun encodeFolders(folders: List<LocalScanFolder>): JSONArray = JSONArray().apply {
        folders.forEach { f -> put(JSONObject().put("volume", f.volumeName).put("path", f.relativePath)) }
    }

    private fun parseFolders(array: JSONArray?): List<LocalScanFolder> = buildList {
        if (array != null) for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val folder = LocalScanFolder.create(item.optString("volume"), item.optString("path"))
            if (folder != null && none { it == folder }) add(folder)
        }
    }
}

/** Normalize Android document volumes to MediaStore VOLUME_NAME (Android 10+). */
internal fun normalizeVolume(value: String): String {
    val v = value.trim().lowercase(Locale.ROOT)
    return if (v == "primary" || v == "external" || v == "external_primary") "external_primary" else v
}

internal fun normalizeDirectory(value: String): String {
    val p = value.trim().replace('\\', '/').trim('/')
    return if (p.isEmpty()) "" else "$p/"
}

/** Escape wildcard characters for the SQL LIKE predicate. */
internal fun escapeForLike(prefix: String): String = prefix
    .replace("!", "!!")
    .replace("%", "!%")
    .replace("_", "!_") + "%"
