package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager

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
    private const val KEY_ENABLED = "enabled"
    private const val KEY_USER_SET = "user_set"
    private const val KEY_LAST_SIM_ID = "last_sim_id"
    private const val KEY_LAST_CHANGE_AT = "last_change_at"

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

    fun lastSimId(context: Context): String? {
        val userId = userId(context) ?: return null
        return prefs(context).getString(key(KEY_LAST_SIM_ID, userId), null)
            ?.takeIf { it.isNotBlank() }
    }

    fun setLastSimId(context: Context, simId: String) {
        val userId = userId(context) ?: return
        if (simId.isBlank()) return
        val prev = lastSimId(context)
        prefs(context).edit().apply {
            putString(key(KEY_LAST_SIM_ID, userId), simId.trim())
            if (prev != null && prev != simId.trim()) {
                putLong(key(KEY_LAST_CHANGE_AT, userId), System.currentTimeMillis())
            }
            commit()
        }
    }

    fun lastChangeAt(context: Context): Long {
        val userId = userId(context) ?: return 0L
        return prefs(context).getLong(key(KEY_LAST_CHANGE_AT, userId), 0L)
    }

    private fun userId(context: Context): String? =
        TokenManager.getInstance(context).getUserId()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(base: String, userId: String) = "$base:$userId"
}
