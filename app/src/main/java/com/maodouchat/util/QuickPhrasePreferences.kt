package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 输入栏快捷短语（常用语）本地存储：按账号隔离。
 * 默认短语由 [QuickPhrasePolicy.DEFAULT_PHRASES] 提供，用户自定义追加在默认列表之后。
 */
object QuickPhrasePreferences {
    private const val PREFS_NAME = "quick_phrase_prefs"
    private const val KEY_PHRASES = "phrases"

    fun getPhrases(context: Context): List<String> {
        val userId = currentUserId(context) ?: return QuickPhrasePolicy.DEFAULT_PHRASES
        val raw = prefs(context).getString(key(KEY_PHRASES, userId), null)
        if (raw.isNullOrBlank()) return QuickPhrasePolicy.DEFAULT_PHRASES
        val custom = PrefsJsonLists.decode(raw)
        return QuickPhrasePolicy.DEFAULT_PHRASES + custom.filter { it !in QuickPhrasePolicy.DEFAULT_PHRASES }
    }

    /** 只返回用户自定义部分（可删除的那部分）。 */
    fun getCustomPhrases(context: Context): List<String> {
        val userId = currentUserId(context) ?: return emptyList()
        val raw = prefs(context).getString(key(KEY_PHRASES, userId), null) ?: return emptyList()
        return PrefsJsonLists.decode(raw)
    }

    fun addPhrase(context: Context, phrase: String) {
        val userId = currentUserId(context) ?: return
        val next = QuickPhrasePolicy.add(getCustomPhrases(context), phrase)
        prefs(context).edit().putString(key(KEY_PHRASES, userId), PrefsJsonLists.encode(next)).apply()
    }

    fun removePhrase(context: Context, phrase: String) {
        val userId = currentUserId(context) ?: return
        val next = QuickPhrasePolicy.remove(getCustomPhrases(context), phrase)
        prefs(context).edit().putString(key(KEY_PHRASES, userId), PrefsJsonLists.encode(next)).apply()
    }

    fun clearForUser(context: Context, userId: String) {
        if (userId.isBlank()) return
        prefs(context).edit().remove(key(KEY_PHRASES, userId)).apply()
    }

    private fun currentUserId(context: Context): String? =
        TokenManager.getInstance(context.applicationContext).getUserId()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(prefix: String, userId: String): String = "${prefix}_$userId"


}
