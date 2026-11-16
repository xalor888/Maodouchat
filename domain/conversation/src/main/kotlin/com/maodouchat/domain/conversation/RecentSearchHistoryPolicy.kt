package com.maodouchat.domain.conversation

// 最近搜索历史去重+截断；SharedPreferences 的 load/persist 仍在 app 侧。
object RecentSearchHistoryPolicy {
    const val MAX_RECENT_SEARCHES = 8

    fun push(existing: List<String>, query: String): List<String> =
        (listOf(query) + existing.filterNot { it.equals(query, ignoreCase = true) })
            .take(MAX_RECENT_SEARCHES)
}
