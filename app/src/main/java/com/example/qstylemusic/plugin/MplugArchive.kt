package com.example.qstylemusic.plugin

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The custom .mplug v1 format is a ZIP containing ONLY plugin.json and main.js.
 * We never extract ZIP entries to a filesystem path; this prevents ZIP path traversal.
 * This is an integrity check, NOT a trusted-author signature verification system.
 */
data class MplugManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val capabilities: List<String>,
    val mainSha256: String,
    val apiVersion: Int = 1,
    val permissions: List<String> = emptyList()
)

data class MplugArchive(val manifest: MplugManifest, val javascript: String)

object MplugArchiveReader {
    const val MAX_PACKAGE_BYTES: Int = 512 * 1024
    private const val MAX_MANIFEST_BYTES: Int = 32 * 1024
    private const val MAX_SCRIPT_BYTES: Int = 256 * 1024
    private val ID_PATTERN = Regex("[a-z][a-z0-9._-]{2,63}")
    private val VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
    private val SHA_PATTERN = Regex("[0-9a-f]{64}")

    /** Reads up to the stated maximum, also protecting against unknown Content-Length. */
    fun readPackageBytes(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val read = input.read(chunk)
            if (read == -1) break
            if (read == 0) continue
            require(out.size() + read <= MAX_PACKAGE_BYTES) {
                "插件文件超过 512 KiB 的 V0.6 限制"
            }
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    fun parse(bytes: ByteArray): MplugArchive {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PACKAGE_BYTES) { "无效的 .mplug 文件大小" }
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                require(count <= 2) { "V0.6 插件只能包含 plugin.json 和 main.js" }
                require(!entry.isDirectory && entry.name in setOf("plugin.json", "main.js")) {
                    "插件包含不允许的 ZIP 路径：${entry.name}"
                }
                require(entry.name !in entries) { "插件中存在重复文件：${entry.name}" }
                val limit = if (entry.name == "plugin.json") MAX_MANIFEST_BYTES else MAX_SCRIPT_BYTES
                val data = readEntry(zip, limit)
                entries[entry.name] = data
                zip.closeEntry()
            }
        }
        require(entries.keys == setOf("plugin.json", "main.js")) {
            "插件缺少 plugin.json 或 main.js"
        }

        val rawManifest = JSONObject(entries.getValue("plugin.json").toString(Charsets.UTF_8))
        val id = rawManifest.getString("id")
        val name = rawManifest.getString("name")
        val version = rawManifest.getString("version")
        val apiVersion = rawManifest.getInt("apiVersion")
        val entry = rawManifest.getString("entry")
        val sha = rawManifest.getString("mainSha256").lowercase()
        require(id.matches(ID_PATTERN)) { "插件 ID 只能使用小写字母、数字、点、下划线和横线" }
        require(name.isNotBlank() && name.length <= 64) { "插件名称不符合规范" }
        require(version.matches(VERSION_PATTERN)) { "插件版本号必须形如 1.0.0" }
        require(apiVersion in 1..2) { "不支持的插件 API 版本：$apiVersion" }
        require(entry == "main.js") { "V0.6 的插件入口必须是 main.js" }
        require(sha.matches(SHA_PATTERN)) { "mainSha256 应为 64 位 SHA-256 十六进制摘要" }
        require(sha == sha256(entries.getValue("main.js"))) { "main.js 摘要不匹配，插件可能已被修改" }
        val description = rawManifest.optString("description", "").take(200)
        val permissions = rawManifest.optJSONArray("permissions")
        val declaredPermissions = buildList {
            if (permissions != null) {
                for (i in 0 until permissions.length()) add(permissions.getString(i))
            }
        }
        require(declaredPermissions.distinct().size == declaredPermissions.size) { "存在重复的插件权限" }
        require(if (apiVersion == 1) declaredPermissions.isEmpty()
            else declaredPermissions.all { it in setOf("http.request", "auth.qr") }) {
            "网络插件权限仅支持 http.request / auth.qr"
        }
        val capsJson = rawManifest.optJSONArray("capabilities")
        val capabilities = buildList {
            if (capsJson != null) {
                for (i in 0 until capsJson.length()) {
                    add(capsJson.getString(i))
                }
            }
        }
        val allowed = if (apiVersion == 1) setOf("getInfo", "search")
            else setOf("getInfo", "search", "lyrics", "resolveStream", "playlist", "loginQr", "login")
        require(capabilities.all { it in allowed } && capabilities.distinct().size == capabilities.size) {
            "插件能力声明不符合 V0.7 规范"
        }
        if (apiVersion == 2) {
            if ("loginQr" in capabilities) {
                require(id == "netease.source" && "auth.qr" in declaredPermissions) { "扫码认证仅限网易云源且必须声明 auth.qr" }
            }
            // QQ authentication is a native Android host service, exposed only to the
            // vetted qqmusic.direct source. Scripts never receive QQ credentials.
            if ("login" in capabilities) {
                require(id == "qqmusic.direct" && "auth.qr" in declaredPermissions) {
                    "QQ 扫码登录只允许受支持的 QQ 音源并要求 auth.qr 权限"
                }
            }
            require(capabilities.contains("getInfo")) { "网络插件必须声明 getInfo" }
            require("http.request" in declaredPermissions) { "网络插件需要 http.request 权限" }
        }
        return MplugArchive(
            MplugManifest(id, name, version, description, capabilities, sha, apiVersion, declaredPermissions),
            entries.getValue("main.js").toString(Charsets.UTF_8)
        )
    }

    private fun readEntry(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = input.read(chunk)
            if (count == -1) break
            if (count == 0) continue
            require(out.size() + count <= limit) { "插件内文件解压后超出大小限制" }
            out.write(chunk, 0, count)
        }
        return out.toByteArray()
    }

    private fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
