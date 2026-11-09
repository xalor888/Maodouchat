package com.maodouchat.messaging.v2

import com.maodouchat.core.model.MessageReceipt
import com.maodouchat.data.local.entity.MessagingV2ReceiptEntity

/** 回执实体与纯领域模型之间的边界映射（字段一一对应，值原样搬运）。 */
internal fun MessagingV2ReceiptEntity.toMessageReceipt() = MessageReceipt(
    ownerUserId = ownerUserId,
    messageId = messageId,
    conversationId = conversationId,
    recipientUserId = recipientUserId,
    deliveredAt = deliveredAt,
    readAt = readAt,
    playedAt = playedAt,
    updatedAt = updatedAt,
)

internal fun MessageReceipt.toReceiptEntity() = MessagingV2ReceiptEntity(
    ownerUserId = ownerUserId,
    messageId = messageId,
    conversationId = conversationId,
    recipientUserId = recipientUserId,
    deliveredAt = deliveredAt,
    readAt = readAt,
    playedAt = playedAt,
    updatedAt = updatedAt,
)
