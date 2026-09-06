package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 表情「最近使用」：按账号隔离的本地记录（与贴纸 recent 同模式）。
 */
object EmojiRecentPreferences {
    private const val PREFS_NAME = "emoji_recent_prefs"
    private const val KEY_RECENT = "recent"
    private const val MAX_RECENT = 36

    fun getRecent(context: Context): List<String> {
        val userId = currentUserId(context) ?: return emptyList()
        val raw = prefs(context).getString(key(KEY_RECENT, userId), null) ?: return emptyList()
        return PrefsJsonLists.decode(raw)
    }

    fun recordRecent(context: Context, emoji: String) {
        val userId = currentUserId(context) ?: return
        val value = emoji.trim()
        if (value.isEmpty()) return
        val next = (listOf(value) + getRecent(context).filter { it != value }).take(MAX_RECENT)
        prefs(context).edit().putString(key(KEY_RECENT, userId), PrefsJsonLists.encode(next)).apply()
    }

    fun clearForUser(context: Context, userId: String) {
        if (userId.isBlank()) return
        prefs(context).edit().remove(key(KEY_RECENT, userId)).apply()
    }

    private fun currentUserId(context: Context): String? =
        TokenManager.getInstance(context.applicationContext).getUserId()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(prefix: String, userId: String): String = "${prefix}_$userId"


}
