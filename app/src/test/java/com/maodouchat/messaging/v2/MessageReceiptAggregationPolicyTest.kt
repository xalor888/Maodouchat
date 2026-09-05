package com.maodouchat.messaging.v2

import com.maodouchat.data.local.entity.MessagingV2ReceiptEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageReceiptAggregationPolicyTest {

    @Test
    fun `delivery receipt records delivery timestamp`() {
        val receipt = MessageReceiptAggregationPolicy.mergeReceipt(
            existing = null,
            ownerUserId = "alice",
            messageId = "msg-1",
            conversationId = "conv-1",
            recipientUserId = "bob",
            deliveredAt = 1000L,
            readAt = null,
            playedAt = null,
            now = 1050L,
        )

        assertEquals("alice", receipt.ownerUserId)
        assertEquals("msg-1", receipt.messageId)
        assertEquals("conv-1", receipt.conversationId)
        assertEquals("bob", receipt.recipientUserId)
        assertEquals(1000L, receipt.deliveredAt)
        assertNull(receipt.readAt)
        assertNull(receipt.playedAt)
        assertEquals(1050L, receipt.updatedAt)
    }

    @Test
    fun `subsequent read receipt retains delivery timestamp and advances readAt`() {
        val existing = MessagingV2ReceiptEntity(
            ownerUserId = "alice",
            messageId = "msg-1",
            conversationId = "conv-1",
            recipientUserId = "bob",
            deliveredAt = 1000L,
            readAt = null,
            playedAt = null,
            updatedAt = 1050L,
        )

        val updated = MessageReceiptAggregationPolicy.mergeReceipt(
            existing = existing,
            ownerUserId = "alice",
            messageId = "msg-1",
            conversationId = "conv-1",
            recipientUserId = "bob",
            deliveredAt = null,
            readAt = 2000L,
            playedAt = null,
            now = 2050L,
        )

        assertEquals(1000L, updated.deliveredAt)
        assertEquals(2000L, updated.readAt)
        assertNull(updated.playedAt)
        assertEquals(2050L, updated.updatedAt)
    }

    @Test
    fun `subsequent play receipt retains delivery and read and advances playedAt`() {
        val existing = MessagingV2ReceiptEntity(
            ownerUserId = "alice",
            messageId = "msg-1",
            conversationId = "conv-1",
            recipientUserId = "bob",
            deliveredAt = 1000L,
            readAt = 2000L,
            playedAt = null,
            updatedAt = 2050L,
        )

        val updated = MessageReceiptAggregationPolicy.mergeReceipt(
            existing = existing,
            ownerUserId = "alice",
            messageId = "msg-1",
            conversationId = "conv-1",
            recipientUserId = "bob",
            deliveredAt = null,
            readAt = null,
            playedAt = 3000L,
            now = 3050L,
        )

        assertEquals(1000L, updated.deliveredAt)
        assertEquals(2000L, updated.readAt)
        assertEquals(3000L, updated.playedAt)
        assertEquals(3050L, updated.updatedAt)
    }

    @Test
    fun `older timestamps never regress existing receipts`() {
        val existing = MessagingV2ReceiptEntity(
            ownerUserId = "alice",
            messageId = "msg-1",
            conversationId = "conv-1",
            recipientUserId = "bob",
            deliveredAt = 5000L,
            readAt = 6000L,
            playedAt = 7000L,
            updatedAt = 7500L,
        )

        val updated = MessageReceiptAggregationPolicy.mergeReceipt(
            existing = existing,
            ownerUserId = "alice",
            messageId = "msg-1",
            conversationId = "conv-1",
            recipientUserId = "bob",
            deliveredAt = 1000L,
            readAt = 2000L,
            playedAt = 3000L,
            now = 8000L,
        )

        assertEquals(5000L, updated.deliveredAt)
        assertEquals(6000L, updated.readAt)
        assertEquals(7000L, updated.playedAt)
        assertEquals(8000L, updated.updatedAt)
    }
}
