package com.maodouchat.util

import android.content.Context
import com.maodouchat.network.TokenManager
import org.json.JSONArray

/**
 * 本机 GIF 最近发送记录（按账号隔离）。
 */
object GifSearchPreferences {
    private const val PREFS_NAME = "gif_search_prefs"
    private const val KEY_RECENT = "recent_ids"

    fun getRecentIds(context: Context): List<String> {
        val userId = currentUserId(context) ?: return emptyList()
        val raw = prefs(context).getString(key(KEY_RECENT, userId), null) ?: return emptyList()
        return decodeList(raw)
    }

    fun recordRecent(context: Context, gifId: String) {
        val userId = currentUserId(context) ?: return
        val next = GifSearchPolicy.pushRecent(getRecentIds(context), gifId)
        prefs(context).edit().putString(key(KEY_RECENT, userId), encodeList(next)).apply()
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

    private fun encodeList(items: List<String>): String {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decodeList(raw: String): List<String> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "").trim()
                if (v.isNotEmpty()) add(v)
            }
        }
    }.getOrDefault(emptyList())
}
