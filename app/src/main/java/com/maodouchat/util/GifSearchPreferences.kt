package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 本机 GIF 最近发送记录（按账号隔离）。
 */
object GifSearchPreferences {
    private const val PREFS_NAME = "gif_search_prefs"
    private const val KEY_RECENT = "recent_ids"

    fun getRecentIds(context: Context): List<String> {
        val userId = currentUserId(context) ?: return emptyList()
        val raw = userScopedPrefs(context, PREFS_NAME).getString(userScopedKey(KEY_RECENT, userId), null) ?: return emptyList()
        return PrefsJsonLists.decode(raw)
    }

    fun recordRecent(context: Context, gifId: String) {
        val userId = currentUserId(context) ?: return
        val next = GifSearchPolicy.pushRecent(getRecentIds(context), gifId)
        userScopedPrefs(context, PREFS_NAME).edit().putString(userScopedKey(KEY_RECENT, userId), PrefsJsonLists.encode(next)).apply()
    }

    fun clearForUser(context: Context, userId: String) {
        if (userId.isBlank()) return
        userScopedPrefs(context, PREFS_NAME).edit().remove(userScopedKey(KEY_RECENT, userId)).apply()
    }



}
