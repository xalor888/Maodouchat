package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ScheduledMessage

// 定时消息搜索：正文含查询词即命中。
// 自 ScheduledMessagesListSheet 的 filteredItems 收敛。
object ScheduledMessageFilterPolicy {

    fun matches(message: ScheduledMessage, query: String): Boolean =
        message.text.contains(query, ignoreCase = true)

    fun filter(items: List<ScheduledMessage>, query: String): List<ScheduledMessage> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return items
        return items.filter { matches(it, trimmed) }
    }
}
