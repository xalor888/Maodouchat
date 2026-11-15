package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.User

// 合并转发：多条文本消息合并为一条发送（带发送者标签）；单条与整体各截断 4_000 字符。
object ForwardMergePolicy {

    fun isMergeable(messages: List<Message>): Boolean =
        messages.size > 1 && messages.all { it.type == MessageType.TEXT || it.type == MessageType.MARKDOWN }

    fun buildMergedText(messages: List<Message>, currentUserId: String, meLabel: String, participants: List<User>?): String =
        messages.joinToString("\n") { msg ->
            val senderLabel = when {
                msg.senderId == currentUserId -> meLabel
                else -> participants?.firstOrNull { it.id == msg.senderId }?.displayName?.takeIf { it.isNotBlank() }
            }
            val content = msg.parsedContent().take(4_000)
            senderLabel?.let { "$it：$content" } ?: content
        }.take(4_000)
}
