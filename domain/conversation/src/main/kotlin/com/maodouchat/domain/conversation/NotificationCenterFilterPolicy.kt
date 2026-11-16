package com.maodouchat.domain.conversation

import com.maodouchat.core.model.NotificationCenterItem

// 通知中心搜索：标题、副标题、预览、类型、附加字段任一含查询词即命中。
// 自 NotificationCenterScreen 的 filteredItems 收敛（类型筛选仍在调用方）。
object NotificationCenterFilterPolicy {

    fun matches(item: NotificationCenterItem, query: String): Boolean =
        item.title.contains(query, ignoreCase = true) ||
            item.subtitle.orEmpty().contains(query, ignoreCase = true) ||
            item.preview.orEmpty().contains(query, ignoreCase = true) ||
            item.type.contains(query, ignoreCase = true) ||
            item.extra.values.any { it.contains(query, ignoreCase = true) }

    fun filter(items: List<NotificationCenterItem>, query: String): List<NotificationCenterItem> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return items
        return items.filter { matches(it, trimmed) }
    }
}
