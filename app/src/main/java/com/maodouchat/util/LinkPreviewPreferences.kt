package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 链接预览开关：按账号隔离；默认开启；未登录时视为关闭（不发起外网请求）。
 */
object LinkPreviewPreferences {
    private const val PREFS_NAME = "chat_display_settings"
    private const val KEY_ENABLED = "link_preview_enabled"

    /** 内存版本号：每次 setEnabled 递增，供 Compose remember 作为 key 刷新缓存。 */
    @Volatile
    var version: Int = 0
        private set

    fun isEnabled(context: Context): Boolean {
        val userId = TokenManager.getInstance(context.applicationContext).getUserId()
            ?.takeIf { it.isNotBlank() } ?: return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(key(userId), true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val userId = TokenManager.getInstance(context.applicationContext).getUserId()
            ?.takeIf { it.isNotBlank() } ?: return
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key(userId), enabled)
            .apply()
        version++
    }

    private fun key(userId: String): String = "${KEY_ENABLED}_$userId"
}
