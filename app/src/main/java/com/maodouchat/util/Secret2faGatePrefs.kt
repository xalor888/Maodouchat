package com.maodouchat.util

import android.content.Context

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
    private val switch = AccountFeatureSwitch("secret_2fa_gate", defaultEnabled = false)

    fun isEnabled(context: Context): Boolean = switch.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) = switch.setEnabled(context, enabled)

    fun isUserSet(context: Context): Boolean = switch.isUserSet(context)

    fun applyServerDefault(context: Context, enabled: Boolean) = switch.applyServerDefault(context, enabled)
    private const val KEY_TIMEOUT_MS = "timeout_ms"
    private const val KEY_LAST_VERIFIED_AT = "last_verified_at"

    /** 默认免验证窗口：5 分钟。 */
    const val DEFAULT_TIMEOUT_MS = 5L * 60 * 1000

    fun gateTimeoutMs(context: Context): Long {
        val userId = switch.userId(context) ?: return DEFAULT_TIMEOUT_MS
        return switch.prefs(context).getLong(switch.key(KEY_TIMEOUT_MS, userId), DEFAULT_TIMEOUT_MS).coerceIn(10_000L, 24L * 60 * 60 * 1000)
    }

    fun setGateTimeoutMs(context: Context, timeoutMs: Long) {
        val userId = switch.userId(context) ?: return
        switch.prefs(context).edit().putLong(switch.key(KEY_TIMEOUT_MS, userId), timeoutMs.coerceIn(10_000L, 24L * 60 * 60 * 1000)).apply()
    }

    fun isGateOpen(context: Context): Boolean {
        if (!isEnabled(context)) return true
        val userId = switch.userId(context) ?: return false
        val last = switch.prefs(context).getLong(switch.key(KEY_LAST_VERIFIED_AT, userId), 0L)
        return last > 0L && System.currentTimeMillis() - last < gateTimeoutMs(context)
    }

    fun markVerified(context: Context) {
        val userId = switch.userId(context) ?: return
        switch.prefs(context).edit().putLong(switch.key(KEY_LAST_VERIFIED_AT, userId), System.currentTimeMillis()).apply()
    }

    fun clearGate(context: Context) {
        val userId = switch.userId(context) ?: return
        switch.prefs(context).edit().remove(switch.key(KEY_LAST_VERIFIED_AT, userId)).apply()
    }
}
