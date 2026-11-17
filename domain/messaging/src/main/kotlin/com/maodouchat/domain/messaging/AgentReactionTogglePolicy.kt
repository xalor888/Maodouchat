package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageReaction

// 消息回应切换，自 app 侧 AgentMessagingMessageTools.react 收敛；
// 数据库读写与事件入队仍留在 app 侧。
object AgentReactionTogglePolicy {

    fun nextEmoji(
        reactions: List<MessageReaction>,
        ownerUserId: String,
        normalizedEmoji: String,
    ): String? = normalizedEmoji.takeUnless {
        reactions.any { it.userId == ownerUserId && it.emoji == normalizedEmoji }
    }

    fun applyReaction(
        reactions: List<MessageReaction>,
        ownerUserId: String,
        nextEmoji: String?,
    ): List<MessageReaction> =
        reactions.filterNot { it.userId == ownerUserId } +
            listOfNotNull(nextEmoji?.let { MessageReaction(ownerUserId, it) })
}
