package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 未读智能优先开关（账号隔离，默认开）。
 */
object UnreadPriorityPreferences {
    private const val PREFS = "unread_priority"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean {
        val userId = userId(context) ?: return true
        return prefs(context).getBoolean(key(KEY_ENABLED, userId), true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val userId = userId(context) ?: return
        prefs(context).edit().putBoolean(key(KEY_ENABLED, userId), enabled).apply()
    }

    private fun userId(context: Context): String? =
        TokenManager.getInstance(context).getUserId()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(base: String, userId: String) = "$base:$userId"
}
