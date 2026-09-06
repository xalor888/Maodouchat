package com.maodouchat.util

import android.content.Context
import android.content.SharedPreferences
import com.maodouchat.network.TokenManager

/**
 * 账号隔离布尔开关委托（密聊 surface 开关八连）。
 *
 * 收敛各 `Secret*Prefs` 逐字相同的核心四件套（isEnabled/setEnabled/
 * isUserSet/applyServerDefault）与账号 prefs 脚手架：同一 PREFS 名、
 * 同一 `$base:$userId` 键格式、同一 userId 为空守卫。
 * 各文件保留自有 const 与特有状态（如 lastSimId），只删共有实现。
 */
class AccountFeatureSwitch(
    private val prefsName: String,
    private val defaultEnabled: Boolean = true,
) {
    fun isEnabled(context: Context): Boolean {
        // 原语义：无账号时 fail-open 返回 true（八文件逐字一致，含默认关的 2fa）。
        val userId = userId(context) ?: return true
        return prefs(context).getBoolean(key(KEY_ENABLED, userId), defaultEnabled)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val userId = userId(context) ?: return
        prefs(context).edit()
            .putBoolean(key(KEY_ENABLED, userId), enabled)
            .putBoolean(key(KEY_USER_SET, userId), true)
            .apply()
    }

    /** 用户是否显式设置过该开关；未设置时接受服务端默认值。 */
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

    fun userId(context: Context): String? =
        TokenManager.getInstance(context).getUserId()?.takeIf { it.isNotBlank() }

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun key(base: String, userId: String): String = "$base:$userId"

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_USER_SET = "user_set"
    }
}
