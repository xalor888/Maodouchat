package com.maodouchat.util

import android.content.Context
import android.content.SharedPreferences
import com.maodouchat.network.TokenManager

/** 1.176：语音消息「已播放」标记（账号隔离；用于气泡未读红点）。 */
object VoicePlayedStore {

    private const val PREFS_NAME = "voice_played"
    private const val KEY_PLAYED_IDS = "played_ids"

    private fun scopedKey(base: String, userId: String): String = "$base:$userId"

    /** 指定语音消息是否已播放过。 */
    fun isPlayed(context: Context, messageId: String): Boolean {
        if (messageId.isBlank()) return true
        val ids = playedIds(context) ?: return false
        return ids.contains(messageId)
    }

    /** 标记语音消息已播放（自然播放完成时调用）。 */
    fun markPlayed(context: Context, messageId: String) {
        if (messageId.isBlank()) return
        val account = accountPreferences(context) ?: return
        val key = scopedKey(KEY_PLAYED_IDS, account.userId)
        val current = account.prefs.getStringSet(key, null) ?: emptySet()
        if (current.contains(messageId)) return
        val updated = LinkedHashSet(current).apply { add(messageId) }
        account.prefs.edit().putStringSet(key, updated).apply()
    }

    private fun playedIds(context: Context): Set<String>? {
        val account = accountPreferences(context) ?: return null
        return account.prefs.getStringSet(scopedKey(KEY_PLAYED_IDS, account.userId), null)
    }

    private fun accountPreferences(context: Context): AccountPreferences? {
        val appContext = context.applicationContext
        val userId = TokenManager.getInstance(appContext).getUserId()?.takeIf(String::isNotBlank) ?: return null
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AccountPreferences(prefs, userId)
    }

    private data class AccountPreferences(val prefs: SharedPreferences, val userId: String)
}
