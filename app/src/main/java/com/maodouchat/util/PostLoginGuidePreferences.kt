package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 首次进入主页引导：按账号隔离；展示后不再弹。
 */
object PostLoginGuidePreferences {
    private const val PREFS_NAME = "post_login_guide"
    private const val KEY_SEEN = "seen"

    fun shouldShow(context: Context): Boolean {
        val userId = TokenManager.getInstance(context.applicationContext).getUserId()
            ?.takeIf { it.isNotBlank() } ?: return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getBoolean(key(userId), false)
    }

    fun markSeen(context: Context) {
        val userId = TokenManager.getInstance(context.applicationContext).getUserId()
            ?.takeIf { it.isNotBlank() } ?: return
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key(userId), true)
            .apply()
    }

    private fun key(userId: String): String = "${KEY_SEEN}_$userId"
}
