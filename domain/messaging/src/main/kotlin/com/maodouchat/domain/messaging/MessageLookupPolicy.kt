package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message

// 按 id 找单条消息：聊天详情 AI 各簇与星标消息 9 处逐字相同的 firstOrNull；
// 调用方保留各自的 ?: return / ?: message 回退。
object MessageLookupPolicy {

    fun findById(messages: List<Message>, messageId: String): Message? =
        messages.firstOrNull { it.id == messageId }
}
