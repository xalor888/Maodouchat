package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageReceipt

/**
 * Pure policy for monotonic receipt timestamp aggregation.
 * Delivery, read, and playback receipts cannot move backward in time.
 */
object MessageReceiptAggregationPolicy {
    fun mergeReceipt(
        existing: MessageReceipt?,
        ownerUserId: String,
        messageId: String,
        conversationId: String,
        recipientUserId: String,
        deliveredAt: Long?,
        readAt: Long? = null,
        playedAt: Long? = null,
        now: Long,
    ): MessageReceipt {
        return MessageReceipt(
            ownerUserId = ownerUserId,
            messageId = messageId,
            conversationId = conversationId,
            recipientUserId = recipientUserId,
            deliveredAt = maxOf(existing?.deliveredAt ?: 0L, deliveredAt ?: 0L).takeIf { it > 0L },
            readAt = maxOf(existing?.readAt ?: 0L, readAt ?: 0L).takeIf { it > 0L },
            playedAt = maxOf(existing?.playedAt ?: 0L, playedAt ?: 0L).takeIf { it > 0L },
            updatedAt = now,
        )
    }
}
