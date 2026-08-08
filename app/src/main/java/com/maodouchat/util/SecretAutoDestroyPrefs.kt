package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 密聊自动销毁开关（B2 surface · 自动销毁，health 名 ttlz）。
 *
 * 开启后，密聊会话超过 [DEFAULT_TTL_SECONDS] 无活动即整体销毁（本地媒体、会话快照一并清除）。
 * 与既有 `secret_auto_disappear_enabled`（单条消息 24h 阅后即焚）互补：
 * 那是「消息级」计时，这是「会话级」TTL 兜底。
 *
 * 账号隔离，默认开；仅本机生效，服务端不接触密聊明文。
 */
object SecretAutoDestroyPrefs {
    private const val PREFS = "secret_auto_destroy"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_USER_SET = "user_set"
    private const val KEY_TTL_SECONDS = "ttl_seconds"

    /** 默认会话 TTL：24 小时。 */
    const val DEFAULT_TTL_SECONDS = 86_400L

    const val MIN_TTL_SECONDS = 300L
    const val MAX_TTL_SECONDS = 30L * 24 * 60 * 60

    fun isEnabled(context: Context): Boolean {
        val userId = userId(context) ?: return true
        return prefs(context).getBoolean(key(KEY_ENABLED, userId), true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val userId = userId(context) ?: return
        prefs(context).edit()
            .putBoolean(key(KEY_ENABLED, userId), enabled)
            .putBoolean(key(KEY_USER_SET, userId), true)
            .apply()
    }

    /** 用户是否显式设置过该开关（设置页写入）；未设置时接受服务端默认值。 */
    fun isUserSet(context: Context): Boolean {
        val userId = userId(context) ?: return false
        return prefs(context).contains(key(KEY_USER_SET, userId))
    }

    /** 服务端下发默认值：仅当用户从未显式设置过时生效（本地开关优先）。 */
    fun applyServerDefault(context: Context, enabled: Boolean) {
        val userId = userId(context) ?: return
        if (isUserSet(context)) return
        prefs(context).edit().putBoolean(key(KEY_ENABLED, userId), enabled).apply()
    }

    fun ttlSeconds(context: Context): Long {
        val userId = userId(context) ?: return DEFAULT_TTL_SECONDS
        return prefs(context).getLong(key(KEY_TTL_SECONDS, userId), DEFAULT_TTL_SECONDS)
            .coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
    }

    fun setTtlSeconds(context: Context, seconds: Long) {
        val userId = userId(context) ?: return
        prefs(context).edit()
            .putLong(key(KEY_TTL_SECONDS, userId), seconds.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS))
            .apply()
    }

    private fun userId(context: Context): String? =
        TokenManager.getInstance(context).getUserId()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(base: String, userId: String) = "$base:$userId"
}
