package com.maodouchat.ui.screen.chatlist

import android.content.Context
import androidx.core.content.edit

/** 最近搜索历史：本机存储，上限 8 条，去重、最新在前。 */
internal object RecentSearches {
    private const val PREFS = "global_search_recent"
    private const val KEY = "queries"
    private const val MAX = 8

    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) add(arr.getString(i))
            }.filter(String::isNotBlank)
        }.getOrDefault(emptyList())
    }

    fun push(context: Context, query: String): List<String> {
        val existing = load(context).filterNot { it.equals(query, ignoreCase = true) }
        val updated = (listOf(query) + existing).take(MAX)
        persist(context, updated)
        return updated
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY) }
    }

    private fun persist(context: Context, queries: List<String>) {
        val arr = org.json.JSONArray()
        queries.forEach(arr::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY, arr.toString()) }
    }
}
