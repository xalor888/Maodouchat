package com.maodouchat.network

import android.content.Context
import com.maodouchat.BuildConfig

/**
 * API 配置
 *
 * 编译期默认值可通过 Gradle 属性覆盖：
 * -PMAODOU_API_BASE_URL=https://api.example.com
 * -PMAODOU_WS_URL=wss://api.example.com/ws
 *
 * 8.45：支持运行时覆盖（设置 → 服务器）——部署方无需重新构建 APK，
 * 直接安装通用包后在应用内填入服务器地址即可（支持 http/https、局域网 IP）。
 */
object ApiConfig {
    private const val PREF_NAME = "maodouchat_server_prefs"
    private const val PREF_KEY_BASE_URL = "runtime_base_url"
    private const val PREF_KEY_WS_URL = "runtime_ws_url"

    @Volatile
    private var runtimeBaseUrl: String? = null
    @Volatile
    private var runtimeWsUrl: String? = null

    val BASE_URL: String get() = runtimeBaseUrl ?: BuildConfig.API_BASE_URL
    val WS_URL: String get() = runtimeWsUrl ?: BuildConfig.WS_URL

    /** 当前是否使用运行时配置的服务器（区别于编译期默认）。 */
    val isUsingRuntimeServer: Boolean get() = runtimeBaseUrl != null

    /** 启动时加载运行时服务器配置（在一切网络调用前调用）。 */
    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        runtimeBaseUrl = prefs.getString(PREF_KEY_BASE_URL, null)?.takeIf(String::isNotBlank)
        runtimeWsUrl = prefs.getString(PREF_KEY_WS_URL, null)?.takeIf(String::isNotBlank)
    }

    /**
     * 设置运行时服务器地址。
     * @param baseUrl 形如 https://chat.example.com 或 http://192.168.1.10:8080
     * @return 校验失败时返回错误文案，成功返回 null 并立即生效
     */
    fun setServer(baseUrl: String, context: Context): String? {
        val normalized = baseUrl.trim().trimEnd('/')
        val validationError = validateBaseUrl(normalized, context)
        if (validationError != null) return validationError
        val wsUrl = wsUrlFor(normalized)
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_KEY_BASE_URL, normalized).putString(PREF_KEY_WS_URL, wsUrl).apply()
        runtimeBaseUrl = normalized
        runtimeWsUrl = wsUrl
        return null
    }

    /** 恢复为编译期默认服务器。 */
    fun resetToDefault(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(PREF_KEY_BASE_URL).remove(PREF_KEY_WS_URL).apply()
        runtimeBaseUrl = null
        runtimeWsUrl = null
    }

    /** 由 HTTP(S) 服务器地址推导 WebSocket 地址（http→ws，https→wss，路径 /ws）。 */
    fun wsUrlFor(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return when {
            normalized.startsWith("https://") -> "wss://${normalized.removePrefix("https://")}/ws"
            normalized.startsWith("http://") -> "ws://${normalized.removePrefix("http://")}/ws"
            else -> "$normalized/ws"
        }
    }

    // 8.48：校验错误资源化（此前硬编码中文，服务器设置页英文用户看到中文）
    private fun validateBaseUrl(value: String, context: Context): String? {
        if (value.isBlank()) return context.getString(com.maodouchat.R.string.server_url_empty)
        val uri = runCatching { android.net.Uri.parse(value) }.getOrNull()
            ?: return context.getString(com.maodouchat.R.string.server_url_invalid)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return context.getString(com.maodouchat.R.string.server_url_scheme)
        val host = uri.host?.trim().orEmpty()
        if (host.isBlank()) return context.getString(com.maodouchat.R.string.server_url_no_host)
        if (host.length > 255) return context.getString(com.maodouchat.R.string.server_url_invalid)
        // 明确拒绝 URL 中的用户名密码/query/fragment（防止误填拖尾路径）
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) {
            return context.getString(com.maodouchat.R.string.server_url_extra)
        }
        val port = uri.port
        if (port != -1 && port !in 1..65535) return context.getString(com.maodouchat.R.string.server_url_port)
        return null
    }

    // Token 存储 Key
    object Prefs {
        const val TOKEN_KEY = "auth_token"
        const val REFRESH_TOKEN_KEY = "refresh_token"
        const val ACCESS_TOKEN_EXPIRES_AT_KEY = "access_token_expires_at"
        const val REFRESH_TOKEN_EXPIRES_AT_KEY = "refresh_token_expires_at"
        const val USER_ID_KEY = "user_id"
        const val PREFS_NAME = "maodouchat_prefs"
        const val LAST_SYNC_AT_KEY = "last_sync_at_ms"
    }
}
