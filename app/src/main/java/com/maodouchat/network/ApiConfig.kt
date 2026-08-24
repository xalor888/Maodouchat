package com.maodouchat.network

import android.content.Context
import com.maodouchat.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    sealed interface ServerChangeResult {
        data object Changed : ServerChangeResult
        data object Unchanged : ServerChangeResult
        data class Failed(val message: String) : ServerChangeResult
    }

    /** 启动时加载运行时服务器配置（在一切网络调用前调用）。 */
    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        runtimeBaseUrl = prefs.getString(PREF_KEY_BASE_URL, null)?.takeIf(String::isNotBlank)
        runtimeWsUrl = prefs.getString(PREF_KEY_WS_URL, null)?.takeIf(String::isNotBlank)
    }

    /**
     * Safely switch trust domains. The previous account and encrypted local data must be
     * purged while the old server is still active; otherwise its access/refresh credentials
     * would be attached to requests sent to the newly configured host.
     */
    suspend fun switchServer(baseUrl: String, context: Context): ServerChangeResult = withContext(Dispatchers.IO) {
        val trimmed = baseUrl.trim()
        val validationError = validateBaseUrl(trimmed, context)
        if (validationError != null) return@withContext ServerChangeResult.Failed(validationError)
        val normalized = normalizeBaseUrl(trimmed)
        val wsUrl = wsUrlFor(normalized)
        val changed = normalized != BASE_URL
        if (changed && !purgeBeforeTrustDomainChange(context)) {
            return@withContext ServerChangeResult.Failed(
                context.getString(com.maodouchat.R.string.settings_server_session_clear_failed)
            )
        }
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // 9.285：保存的地址等于编译期官服时按「恢复默认」处理——不写入 runtime 配置，
        // 避免用户只是重新保存官服地址就被误判为第三方服务器模式
        if (normalized == normalizeBaseUrl(BuildConfig.API_BASE_URL)) {
            val cleared = prefs.edit().remove(PREF_KEY_BASE_URL).remove(PREF_KEY_WS_URL).commit()
            if (!cleared) {
                return@withContext ServerChangeResult.Failed(
                    context.getString(com.maodouchat.R.string.settings_server_save_failed)
                )
            }
            runtimeBaseUrl = null
            runtimeWsUrl = null
            return@withContext if (changed) ServerChangeResult.Changed else ServerChangeResult.Unchanged
        }
        val persisted = prefs.edit()
            .putString(PREF_KEY_BASE_URL, normalized)
            .putString(PREF_KEY_WS_URL, wsUrl)
            .commit()
        if (!persisted) {
            return@withContext ServerChangeResult.Failed(
                context.getString(com.maodouchat.R.string.settings_server_save_failed)
            )
        }
        runtimeBaseUrl = normalized
        runtimeWsUrl = wsUrl
        if (changed) ServerChangeResult.Changed else ServerChangeResult.Unchanged
    }

    /** 校验但暂不写入，供保存前测试连接。 */
    fun validateServerAddress(baseUrl: String, context: Context): String? =
        validateBaseUrl(baseUrl.trim(), context)

    /** 测试地址是否指向可用的 Maodouchat 服务。 */
    fun testConnection(baseUrl: String): Boolean {
        val normalized = normalizeBaseUrl(baseUrl.trim())
        return runCatching {
            val connection = URL("$normalized/health/ready").openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.requestMethod = "GET"
            try {
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    /** Restore the build-time server with the same cross-domain session isolation. */
    suspend fun resetToDefault(context: Context): ServerChangeResult = withContext(Dispatchers.IO) {
        val changed = BASE_URL != normalizeBaseUrl(BuildConfig.API_BASE_URL)
        if (changed && !purgeBeforeTrustDomainChange(context)) {
            return@withContext ServerChangeResult.Failed(
                context.getString(com.maodouchat.R.string.settings_server_session_clear_failed)
            )
        }
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val persisted = prefs.edit().remove(PREF_KEY_BASE_URL).remove(PREF_KEY_WS_URL).commit()
        if (!persisted) {
            return@withContext ServerChangeResult.Failed(
                context.getString(com.maodouchat.R.string.settings_server_save_failed)
            )
        }
        runtimeBaseUrl = null
        runtimeWsUrl = null
        if (changed) ServerChangeResult.Changed else ServerChangeResult.Unchanged
    }

    private suspend fun purgeBeforeTrustDomainChange(context: Context): Boolean {
        val app = context.applicationContext as? com.maodouchat.MaodouchatApp ?: return false
        return try {
            app.secureSessionManager.purgeLocalSession(
                destroyEncryptedDatabase = com.maodouchat.security.LogoutStorePolicy.destroyEncryptedDatabase(
                    com.maodouchat.security.LogoutStorePolicy.Reason.TRUST_DOMAIN_CHANGE
                )
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    /** 由 HTTP(S) 服务器地址推导 WebSocket 地址（http→ws，https→wss，路径 /ws）。 */
    fun wsUrlFor(baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        val schemeEnd = normalized.indexOf("://")
        val lowerScheme = if (schemeEnd > 0) normalized.substring(0, schemeEnd).lowercase() else ""
        return when {
            lowerScheme == "https" -> "wss://${normalized.substring(schemeEnd + 3)}/ws"
            lowerScheme == "http" -> "ws://${normalized.substring(schemeEnd + 3)}/ws"
            else -> "$normalized/ws"
        }
    }

    private fun normalizeBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        val schemeEnd = normalized.indexOf("://")
        if (schemeEnd <= 0) return normalized
        return normalized.substring(0, schemeEnd).lowercase() + normalized.substring(schemeEnd)
    }

    // 8.48：校验错误资源化（此前硬编码中文，服务器设置页英文用户看到中文）
    private fun validateBaseUrl(value: String, context: Context): String? {
        val problem = ServerUrlPolicy.validate(value) ?: return null
        return when (problem) {
            ServerUrlPolicy.Problem.EMPTY -> context.getString(com.maodouchat.R.string.server_url_empty)
            ServerUrlPolicy.Problem.INVALID -> context.getString(com.maodouchat.R.string.server_url_invalid)
            ServerUrlPolicy.Problem.SCHEME -> context.getString(com.maodouchat.R.string.server_url_scheme)
            ServerUrlPolicy.Problem.HOST -> context.getString(com.maodouchat.R.string.server_url_no_host)
            ServerUrlPolicy.Problem.EXTRA -> context.getString(com.maodouchat.R.string.server_url_extra)
            ServerUrlPolicy.Problem.PORT -> context.getString(com.maodouchat.R.string.server_url_port)
        }
    }

    // Token 存储 Key
    object Prefs {
        const val TOKEN_KEY = "auth_token"
        const val REFRESH_TOKEN_KEY = "refresh_token"
        const val ACCESS_TOKEN_EXPIRES_AT_KEY = "access_token_expires_at"
        const val REFRESH_TOKEN_EXPIRES_AT_KEY = "refresh_token_expires_at"
        const val USER_ID_KEY = "user_id"
        /** Survives logout token clear so same-account re-login can keep the SQLCipher + Signal store. */
        const val LAST_OWNER_USER_ID_KEY = "last_owner_user_id"
        const val PREFS_NAME = "maodouchat_prefs"
        const val LAST_SYNC_AT_KEY = "last_sync_at_ms"
    }
}
