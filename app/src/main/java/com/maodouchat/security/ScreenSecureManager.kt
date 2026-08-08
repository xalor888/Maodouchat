package com.maodouchat.security

import com.maodouchat.util.RuntimeFlags
import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 全局「防止截屏/录屏」偏好，按账号隔离；默认关闭以免影响用户分享聊天截图。
 */
object ScreenSecureManager {
    private const val PREFS = "screen_secure"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean {
        if (!RuntimeFlags.isEnabled(context, RuntimeFlags.SCREEN_SECURE)) return false
        val userId = userId(context)
        if (userId.isBlank()) return false
        return prefs(context).getBoolean(key(KEY_ENABLED, userId), false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val userId = userId(context)
        if (userId.isBlank()) return
        prefs(context).edit().putBoolean(key(KEY_ENABLED, userId), enabled).apply()
    }

    fun clearForUser(context: Context, userId: String) {
        if (userId.isBlank()) return
        prefs(context).edit().remove(key(KEY_ENABLED, userId)).apply()
    }

    private fun userId(ctx: Context): String =
        TokenManager.getInstance(ctx).getUserId().orEmpty()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(base: String, userId: String): String = "$base:$userId"
}
