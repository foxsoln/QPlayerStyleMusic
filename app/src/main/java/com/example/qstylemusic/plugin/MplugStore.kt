package com.example.qstylemusic.plugin

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Stores .mplug packages in app-private storage, independent of the Android APK. */
class MplugStore(context: Context) {
    private val appContext = context.applicationContext
    private val folder = File(appContext.filesDir, "mplug").apply { mkdirs() }
    private val prefs = appContext.getSharedPreferences("mplug_enabled_v1", Context.MODE_PRIVATE)

    data class Candidate(val bytes: ByteArray, val manifest: MplugManifest)
    data class Installed(val manifest: MplugManifest, val enabled: Boolean)

    /** Inspect and validate before asking the user to confirm installation. */
    fun prepareInstall(uri: Uri): Candidate {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: error("无法读取所选插件文件")
        val bytes = input.use { MplugArchiveReader.readPackageBytes(it) }
        return Candidate(bytes, MplugArchiveReader.parse(bytes).manifest)
    }

    /** Atomic replace for updates. Existing plugins keep their enable/disable preference. */
    fun install(candidate: Candidate): Installed {
        val parsed = MplugArchiveReader.parse(candidate.bytes)
        require(parsed.manifest == candidate.manifest) { "插件数据已改变，请重新选择文件" }
        val target = packageFile(parsed.manifest.id)
        if (target.exists()) {
            val current = MplugArchiveReader.parse(target.readBytes()).manifest
            require(compareVersions(parsed.manifest.version, current.version) >= 0) {
                "不允许安装低于现有版本 ${current.version} 的插件"
            }
        }
        val temp = File.createTempFile("install-", ".tmp", folder)
        try {
            temp.writeBytes(candidate.bytes)
            try {
                Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
        return Installed(parsed.manifest, prefs.getBoolean(parsed.manifest.id, true))
    }

    fun list(): List<Installed> = folder.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "mplug" && it.length() <= MplugArchiveReader.MAX_PACKAGE_BYTES }
        .mapNotNull { file ->
            runCatching {
                val data = MplugArchiveReader.parse(file.readBytes())
                require(file.name == "${data.manifest.id}.mplug")
                Installed(data.manifest, prefs.getBoolean(data.manifest.id, true))
            }.getOrNull()
        }
        .sortedBy { it.manifest.name }

    fun setEnabled(id: String, enabled: Boolean) {
        require(packageFile(id).exists()) { "插件不存在" }
        prefs.edit().putBoolean(id, enabled).apply()
    }

    fun remove(id: String) {
        val file = packageFile(id)
        require(!file.exists() || file.delete()) { "无法卸载插件" }
        prefs.edit().remove(id).apply()
    }

    fun loadEnabled(id: String): MplugArchive {
        require(prefs.getBoolean(id, true)) { "插件已被禁用" }
        val file = packageFile(id)
        require(file.exists()) { "插件尚未安装" }
        return MplugArchiveReader.parse(file.readBytes())
    }

    private fun packageFile(id: String): File {
        require(id.matches(Regex("[a-z][a-z0-9._-]{2,63}"))) { "非法插件 ID" }
        return File(folder, "$id.mplug")
    }

    private fun compareVersions(a: String, b: String): Int {
        val aa = a.split('.').map(String::toInt)
        val bb = b.split('.').map(String::toInt)
        for (i in 0..2) {
            val comparison = aa[i].compareTo(bb[i])
            if (comparison != 0) return comparison
        }
        return 0
    }
}
