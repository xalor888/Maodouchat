package com.maodouchat.messaging.v2

import com.maodouchat.data.local.entity.MessagingV2ReceiptEntity

/**
 * Pure policy for monotonic receipt timestamp aggregation.
 * Delivery, read, and playback receipts cannot move backward in time.
 */
internal object MessageReceiptAggregationPolicy {
    fun mergeReceipt(
        existing: MessagingV2ReceiptEntity?,
        ownerUserId: String,
        messageId: String,
        conversationId: String,
        recipientUserId: String,
        deliveredAt: Long?,
        readAt: Long? = null,
        playedAt: Long? = null,
        now: Long,
    ): MessagingV2ReceiptEntity {
        return MessagingV2ReceiptEntity(
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
