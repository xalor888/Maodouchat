package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager

/**
 * 贴纸最近使用 / 启用包：按账号隔离。
 */
object StickerPreferences {
    private const val PREFS_NAME = "sticker_prefs"
    private const val KEY_RECENT = "recent"
    private const val KEY_ENABLED_PACKS = "enabled_packs"

    fun getRecent(context: Context): List<String> {
        val userId = currentUserId(context) ?: return emptyList()
        val raw = prefs(context).getString(key(KEY_RECENT, userId), null) ?: return emptyList()
        return PrefsJsonLists.decode(raw)
    }

    fun recordRecent(context: Context, sticker: String) {
        val userId = currentUserId(context) ?: return
        val next = StickerPolicy.pushRecent(getRecent(context), sticker)
        prefs(context).edit().putString(key(KEY_RECENT, userId), PrefsJsonLists.encode(next)).apply()
    }

    fun getEnabledPackIds(context: Context): List<String> {
        val userId = currentUserId(context) ?: return StickerCatalog.defaultEnabledPackIds()
        val raw = prefs(context).getString(key(KEY_ENABLED_PACKS, userId), null)
            ?: return StickerCatalog.defaultEnabledPackIds()
        return StickerPolicy.normalizeEnabledPackIds(PrefsJsonLists.decode(raw))
    }

    fun setEnabledPackIds(context: Context, packIds: List<String>) {
        val userId = currentUserId(context) ?: return
        val normalized = StickerPolicy.normalizeEnabledPackIds(packIds)
        prefs(context).edit().putString(key(KEY_ENABLED_PACKS, userId), PrefsJsonLists.encode(normalized)).apply()
    }

    fun clearForUser(context: Context, userId: String) {
        if (userId.isBlank()) return
        prefs(context).edit()
            .remove(key(KEY_RECENT, userId))
            .remove(key(KEY_ENABLED_PACKS, userId))
            .apply()
    }

    private fun currentUserId(context: Context): String? =
        TokenManager.getInstance(context.applicationContext).getUserId()?.takeIf { it.isNotBlank() }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(prefix: String, userId: String): String = "${prefix}_$userId"


}
