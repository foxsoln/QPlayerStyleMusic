package com.example.qstylemusic.plugin.auth

import android.content.Context

/** 这里只保存服务地址。电脑展示口令只在当前登录页面内存中使用。 */
class QqMusicBridgeSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "qstyle_qqmusic_bridge_config", Context.MODE_PRIVATE
    )

    var backendUrl: String
        get() = prefs.getString("backend_url", "http://127.0.0.1:3200").orEmpty()
        set(value) { prefs.edit().putString("backend_url", value.trim().trimEnd('/')).apply() }

    var pcDisplayUrl: String
        get() = prefs.getString("pc_display_url", "http://127.0.0.1:3210").orEmpty()
        set(value) { prefs.edit().putString("pc_display_url", value.trim().trimEnd('/')).apply() }
}
