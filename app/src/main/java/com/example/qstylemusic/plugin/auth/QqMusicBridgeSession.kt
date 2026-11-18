package com.example.qstylemusic.plugin.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本地加密保存自建 QQ API 服务签发的 opaque session token。
 * 不可与老版本 WebView 抓取的 Cookie 混用。
 */
class QqMusicBridgeSession(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "qstyle_qqmusic_bridge_session_v2", Context.MODE_PRIVATE
    )
    private val alias = "qstyle_qqmusic_bridge_session_key_v2"

    fun read(): String? {
        val stored = prefs.getString("session", null) ?: return null
        return runCatching {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            require(raw.size > 28)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(c.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun save(value: String) {
        require(value.isNotBlank())
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("session", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
}
