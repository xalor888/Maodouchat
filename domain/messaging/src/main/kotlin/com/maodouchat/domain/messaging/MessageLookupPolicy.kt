package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType

// 按 id 找单条消息：聊天详情 AI 各簇与星标消息 9 处逐字相同的 firstOrNull；
// 调用方保留各自的 ?: return / ?: message 回退。
object MessageLookupPolicy {

    fun findById(messages: List<Message>, messageId: String): Message? =
        messages.firstOrNull { it.id == messageId }

    // 按 id + 类型找消息：翻译/图片/文件/语音分析四处逐字同构的 firstOrNull，
    // 仅类型谓词不同（翻译取 TEXT 或 MARKDOWN，其余各取单一类型）。
    fun findByIdAndType(messages: List<Message>, messageId: String, vararg types: MessageType): Message? =
        messages.firstOrNull { it.id == messageId && it.type in types }
}
