package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ScheduledMessage

// 定时消息横幅只预览前 3 条；列表 4 条及以上才出搜索框，搜索词截断 120 字。
object ScheduledMessageBannerPolicy {

    const val PREVIEW_LIMIT: Int = 3
    const val SEARCH_MAX_LENGTH: Int = 120

    fun preview(items: List<ScheduledMessage>): List<ScheduledMessage> =
        items.take(PREVIEW_LIMIT)

    fun hasMore(size: Int): Boolean = size > PREVIEW_LIMIT

    fun showSearchField(size: Int): Boolean = size >= PREVIEW_LIMIT + 1

    fun clipSearchQuery(query: String): String = query.take(SEARCH_MAX_LENGTH)
}
