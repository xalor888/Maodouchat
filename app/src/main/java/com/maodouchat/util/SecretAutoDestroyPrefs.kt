package com.maodouchat.util

import android.content.Context

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
    private val switch = AccountFeatureSwitch("secret_auto_destroy")

    fun isEnabled(context: Context): Boolean = switch.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) = switch.setEnabled(context, enabled)

    fun isUserSet(context: Context): Boolean = switch.isUserSet(context)

    fun applyServerDefault(context: Context, enabled: Boolean) = switch.applyServerDefault(context, enabled)
    private const val KEY_TTL_SECONDS = "ttl_seconds"

    /** 默认会话 TTL：24 小时。 */
    const val DEFAULT_TTL_SECONDS = 86_400L

    const val MIN_TTL_SECONDS = 300L
    const val MAX_TTL_SECONDS = 30L * 24 * 60 * 60

    fun ttlSeconds(context: Context): Long {
        val userId = switch.userId(context) ?: return DEFAULT_TTL_SECONDS
        return switch.prefs(context).getLong(switch.key(KEY_TTL_SECONDS, userId), DEFAULT_TTL_SECONDS)
            .coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
    }

    fun setTtlSeconds(context: Context, seconds: Long) {
        val userId = switch.userId(context) ?: return
        switch.prefs(context).edit()
            .putLong(switch.key(KEY_TTL_SECONDS, userId), seconds.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS))
            .apply()
    }
}
