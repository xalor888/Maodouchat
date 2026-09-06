package com.maodouchat.util

import android.content.Context

/**
 * 密聊 SIM 变更防护开关（B2 surface · SIM 变更防护，health 名 simz）。
 *
 * 开启后记录当前 SIM 标识（subscriber id / sim serial），检测到 SIM 卡被拔出/更换时
 * （见 [com.maodouchat.security.SimChangeWatcher]）立即锁定并清除密聊会话数据，
 * 防止设备落入他人之手后密聊内容被读取。
 *
 * 账号隔离，默认开；仅本机生效，服务端不接触密聊明文。
 */
object SecretSimChangePrefs {
    private const val PREFS = "secret_sim_change"
    private val switch = AccountFeatureSwitch("secret_sim_change")

    fun isEnabled(context: Context): Boolean = switch.isEnabled(context)

    fun setEnabled(context: Context, enabled: Boolean) = switch.setEnabled(context, enabled)

    fun isUserSet(context: Context): Boolean = switch.isUserSet(context)

    fun applyServerDefault(context: Context, enabled: Boolean) = switch.applyServerDefault(context, enabled)
    private const val KEY_LAST_SIM_ID = "last_sim_id"
    private const val KEY_LAST_CHANGE_AT = "last_change_at"

    fun lastSimId(context: Context): String? {
        val userId = switch.userId(context) ?: return null
        return switch.prefs(context).getString(switch.key(KEY_LAST_SIM_ID, userId), null)
            ?.takeIf { it.isNotBlank() }
    }

    fun setLastSimId(context: Context, simId: String) {
        val userId = switch.userId(context) ?: return
        if (simId.isBlank()) return
        val prev = lastSimId(context)
        switch.prefs(context).edit().apply {
            putString(switch.key(KEY_LAST_SIM_ID, userId), simId.trim())
            if (prev != null && prev != simId.trim()) {
                putLong(switch.key(KEY_LAST_CHANGE_AT, userId), System.currentTimeMillis())
            }
            commit()
        }
    }

    fun lastChangeAt(context: Context): Long {
        val userId = switch.userId(context) ?: return 0L
        return switch.prefs(context).getLong(switch.key(KEY_LAST_CHANGE_AT, userId), 0L)
    }
}
