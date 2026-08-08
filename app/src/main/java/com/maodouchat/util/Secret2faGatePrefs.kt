package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 密聊双因素门禁开关（B2 surface · 双因素门禁，health 名 2faz）。
 *
 * 开启后，进入密聊会话前需要额外二次验证（如 App 锁 / 生物识别，见接入方实现），
 * 验证通过后在 [KEY_GATE_TIMEOUT_MS] 内免重复验证。默认关闭，避免无验证器时锁死会话。
 *
 * 账号隔离，默认关；仅本机生效，服务端不接触密聊明文。
 */
object Secret2faGatePrefs {
    private const val PREFS = "secret_2fa_gate"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_USER_SET = "user_set"
    private const val KEY_TIMEOUT_MS = "timeout_ms"
    private const val KEY_LAST_VERIFIED_AT = "last_verified_at"

    /** 默认免验证窗口：5 分钟。 */
    const val DEFAULT_TIMEOUT_MS = 5L * 60 * 1000

    fun isEnabled(context: Context): Boolean {
        val userId = userId(context) ?: return false
        return prefs(context).getBoolean(key(KEY_ENABLED, userId), false)
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

    fun gateTimeoutMs(context: Context): Long {
        val userId = userId(context) ?: return DEFAULT_TIMEOUT_MS
        return prefs(context).getLong(key(KEY_TIMEOUT_MS, userId), DEFAULT_TIMEOUT_MS).coerceIn(10_000L, 24L * 60 * 60 * 1000)
    }

    fun setGateTimeoutMs(context: Context, timeoutMs: Long) {
        val userId = userId(context) ?: return
        prefs(context).edit().putLong(key(KEY_TIMEOUT_MS, userId), timeoutMs.coerceIn(10_000L, 24L * 60 * 60 * 1000)).apply()
    }

    fun isGateOpen(context: Context): Boolean {
        if (!isEnabled(context)) return true
        val userId = userId(context) ?: return false
        val last = prefs(context).getLong(key(KEY_LAST_VERIFIED_AT, userId), 0L)
        return last > 0L && System.currentTimeMillis() - last < gateTimeoutMs(context)
    }

    fun markVerified(context: Context) {
        val userId = userId(context) ?: return
        prefs(context).edit().putLong(key(KEY_LAST_VERIFIED_AT, userId), System.currentTimeMillis()).apply()
    }

    fun clearGate(context: Context) {
        val userId = userId(context) ?: return
        prefs(context).edit().remove(key(KEY_LAST_VERIFIED_AT, userId)).apply()
    }

    private fun userId(context: Context): String? =
        TokenManager.getInstance(context).getUserId()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(base: String, userId: String) = "$base:$userId"
}
