package com.example.qstylemusic.plugin

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.evaluate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * JavaScript execution v1: NO Android object, file IO, network or credential bridge.
 * Only author-written / trusted offline packages should be installed at this stage.
 * Every request uses a fresh QuickJS instance and has an execution timeout.
 */
class MplugJsRuntime {
    suspend fun call(
        plugin: MplugArchive,
        method: String,
        params: JSONObject = JSONObject()
    ): String = withTimeout(3_000L) {
        val allowed = if (plugin.manifest.apiVersion == 1) {
            setOf("getInfo", "search")
        } else {
            setOf("getInfo", "buildRequest", "parseResponse")
        }
        require(method in allowed) { "插件 API 不支持方法 $method" }
        if (method == "getInfo" || plugin.manifest.apiVersion == 1) {
            require(method in plugin.manifest.capabilities) { "插件没有声明 $method 功能" }
        }
        val engine = QuickJs.create(Dispatchers.Default)
        try {
            engine.evaluationTimeoutMillis = 2_000
            // Force a simple primitive result for the initialization statement.
            engine.evaluate<Any?>(plugin.javascript + "\nvoid 0;", filename = "main.js")
            val key = JSONObject.quote(method)
            val payload = JSONObject.quote(params.toString())
            val script = """
                (function() {
                    const source = globalThis.Mplug;
                    if (!source || typeof source[$key] !== 'function') {
                        throw new Error('main.js 未实现 ' + $key);
                    }
                    const result = source[$key](JSON.parse($payload));
                    if (result && typeof result.then === 'function') {
                        throw new Error('V0.6 插件方法必须同步返回 JSON 数据');
                    }
                    return JSON.stringify(result === undefined ? null : result);
                })();
            """.trimIndent()
            engine.evaluate<String>(script, filename = "call.js")
        } finally {
            engine.close()
        }
    }
}
