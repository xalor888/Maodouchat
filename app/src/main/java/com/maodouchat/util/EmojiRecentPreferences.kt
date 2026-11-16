package com.maodouchat.util

import android.content.Context
import androidx.core.content.edit
import com.maodouchat.core.model.PrefsJsonLists
import com.maodouchat.domain.messaging.EmojiRecentPolicy

/**
 * 表情「最近使用」：按账号隔离的本地记录（与贴纸 recent 同模式）。
 */
object EmojiRecentPreferences {
    private const val PREFS_NAME = "emoji_recent_prefs"
    private const val KEY_RECENT = "recent"

    fun getRecent(context: Context): List<String> {
        val userId = currentUserId(context) ?: return emptyList()
        val raw = userScopedPrefs(context, PREFS_NAME).getString(userScopedKey(KEY_RECENT, userId), null) ?: return emptyList()
        return PrefsJsonLists.decode(raw)
    }

    fun recordRecent(context: Context, emoji: String) {
        val userId = currentUserId(context) ?: return
        val value = emoji.trim()
        if (value.isEmpty()) return
        val next = EmojiRecentPolicy.pushRecent(getRecent(context), value)
        userScopedPrefs(context, PREFS_NAME).edit { putString(userScopedKey(KEY_RECENT, userId), PrefsJsonLists.encode(next)) }
    }

    fun clearForUser(context: Context, userId: String) {
        if (userId.isBlank()) return
        userScopedPrefs(context, PREFS_NAME).edit { remove(userScopedKey(KEY_RECENT, userId)) }
    }



}
