package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MAX_AI_SUMMARY_MESSAGES
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType

// 未读 AI 摘要的候选消息筛选与缓存键：自 app 侧 ChatDetailAiContext.kt 的
// maybeGenerateUnreadSummary 逐字平移，仅把闭包变量 currentUserId / unreadCount 改成参数。
fun selectUnreadSummaryCandidates(
    messages: List<Message>,
    currentUserId: String,
    unreadCount: Int,
): List<Message> = messages
    .filter { message ->
        message.senderId != currentUserId &&
            (message.type == MessageType.TEXT || message.type == MessageType.MARKDOWN) &&
            !message.parsedMeta().aiAssisted &&
            message.parsedContent().isNotBlank()
    }
    .sortedBy { it.timestamp }
    .takeLast(unreadCount.coerceIn(1, MAX_AI_SUMMARY_MESSAGES))

fun buildUnreadSummaryCacheKey(
    chatId: String,
    firstMessageId: String,
    lastMessageId: String,
    messageCount: Int,
): String = "$chatId:$firstMessageId:$lastMessageId:$messageCount"
