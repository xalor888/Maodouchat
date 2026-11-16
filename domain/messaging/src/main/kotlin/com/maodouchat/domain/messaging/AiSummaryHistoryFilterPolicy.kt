package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiSummaryHistoryUi

// AI 摘要历史搜索：摘要正文、范围名、缓存键任一含查询词即命中。
// 自 AiSummaryHistoryDialog 的 filteredSummaries 收敛。
object AiSummaryHistoryFilterPolicy {

    fun matches(item: AiSummaryHistoryUi, query: String): Boolean =
        item.summary.contains(query, ignoreCase = true) ||
            item.scope.name.contains(query, ignoreCase = true) ||
            item.cacheKey.contains(query, ignoreCase = true)

    fun filter(
        summaries: List<AiSummaryHistoryUi>,
        query: String
    ): List<AiSummaryHistoryUi> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return summaries
        return summaries.filter { matches(it, trimmed) }
    }
}
