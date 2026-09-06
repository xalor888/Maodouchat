package com.maodouchat.util

import android.content.Context
import android.content.SharedPreferences
import com.maodouchat.network.TokenManager

/**
 * 账号作用域 SharedPreferences 持有人。
 *
 * 收敛 ComposerPreferences / NotificationPreferences 各自私有的同构脚手架：
 * 同一 `$base:$userId` 键格式、同一无账号守卫、同一 get/put 直读直写。
 * 各文件保留自有 PREFS 名、迁移逻辑与领域方法。
 */
class AccountScopedPrefs private constructor(
    val prefs: SharedPreferences,
    val userId: String,
) {
    fun scopedKey(base: String): String = "$base:$userId"

    fun getBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(scopedKey(key), default)

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(scopedKey(key), value).apply()
    }

    fun getInt(key: String, default: Int): Int =
        prefs.getInt(scopedKey(key), default)

    companion object {
        fun of(context: Context, prefsName: String): AccountScopedPrefs? {
            val appContext = context.applicationContext
            val userId = TokenManager.getInstance(appContext).getUserId()
                ?.takeIf(String::isNotBlank) ?: return null
            return AccountScopedPrefs(
                appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE),
                userId,
            )
        }
    }
}
