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

    @Test
    fun `incoming null timestamps leave existing values untouched`() {
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
            deliveredAt = null,
            readAt = null,
            playedAt = null,
            now = 9000L,
        )

        assertEquals(5000L, updated.deliveredAt)
        assertEquals(6000L, updated.readAt)
        assertEquals(7000L, updated.playedAt)
        assertEquals(9000L, updated.updatedAt)
    }

    @Test
    fun `zero timestamps behave like missing`() {
        val fresh = MessageReceiptAggregationPolicy.mergeReceipt(
            existing = null,
            ownerUserId = "alice",
            messageId = "msg-1",
            conversationId = "conv-1",
            recipientUserId = "bob",
            deliveredAt = 0L,
            readAt = 0L,
            playedAt = 0L,
            now = 1050L,
        )

        assertNull(fresh.deliveredAt)
        assertNull(fresh.readAt)
        assertNull(fresh.playedAt)
        assertEquals(1050L, fresh.updatedAt)
    }

    @Test
    fun `duplicate receipt merge is idempotent`() {
        val first = MessageReceiptAggregationPolicy.mergeReceipt(
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

        val second = MessageReceiptAggregationPolicy.mergeReceipt(
            existing = first,
            ownerUserId = "alice",
            messageId = "msg-1",
            conversationId = "conv-1",
            recipientUserId = "bob",
            deliveredAt = 1000L,
            readAt = null,
            playedAt = null,
            now = 1050L,
        )

        assertEquals(first, second)
    }

    @Test
    fun `merge under a new owner re-keys the receipt identity`() {
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
            ownerUserId = "carol",
            messageId = "msg-2",
            conversationId = "conv-2",
            recipientUserId = "dave",
            deliveredAt = 9000L,
            readAt = null,
            playedAt = null,
            now = 9100L,
        )

        assertEquals("carol", updated.ownerUserId)
        assertEquals("msg-2", updated.messageId)
        assertEquals("conv-2", updated.conversationId)
        assertEquals("dave", updated.recipientUserId)
        assertEquals(9000L, updated.deliveredAt)
        assertEquals(6000L, updated.readAt)
        assertEquals(7000L, updated.playedAt)
        assertEquals(9100L, updated.updatedAt)
    }

    @Test
    fun `updatedAt follows now even when it moves backward`() {
        val existing = MessagingV2ReceiptEntity(
            ownerUserId = "alice",
            messageId = "msg-1",
            conversationId = "conv-1",
            recipientUserId = "bob",
            deliveredAt = 5000L,
            readAt = 6000L,
            playedAt = 7000L,
            updatedAt = 8000L,
        )

        // updatedAt is a last-write stamp, not a monotonic clock.
        val updated = MessageReceiptAggregationPolicy.mergeReceipt(
            existing = existing,
            ownerUserId = "alice",
            messageId = "msg-1",
            conversationId = "conv-1",
            recipientUserId = "bob",
            deliveredAt = 4000L,
            readAt = null,
            playedAt = null,
            now = 1000L,
        )

        assertEquals(5000L, updated.deliveredAt)
        assertEquals(1000L, updated.updatedAt)
    }

    @Test
    fun `negative timestamps behave like missing`() {
        val fresh = MessageReceiptAggregationPolicy.mergeReceipt(
            existing = null,
            ownerUserId = "alice",
            messageId = "msg-1",
            conversationId = "conv-1",
            recipientUserId = "bob",
            deliveredAt = -100L,
            readAt = -5L,
            playedAt = -1L,
            now = 1050L,
        )

        assertNull(fresh.deliveredAt)
        assertNull(fresh.readAt)
        assertNull(fresh.playedAt)
        assertEquals(1050L, fresh.updatedAt)
    }

    @Test
    fun `partial advance leaves the other fields untouched`() {
        val existing = MessagingV2ReceiptEntity(
            ownerUserId = "alice",
            messageId = "msg-1",
            conversationId = "conv-1",
            recipientUserId = "bob",
            deliveredAt = 5000L,
            readAt = null,
            playedAt = null,
            updatedAt = 5050L,
        )

        val updated = MessageReceiptAggregationPolicy.mergeReceipt(
            existing = existing,
            ownerUserId = "alice",
            messageId = "msg-1",
            conversationId = "conv-1",
            recipientUserId = "bob",
            deliveredAt = null,
            readAt = 3000L,
            playedAt = null,
            now = 3050L,
        )

        assertEquals(5000L, updated.deliveredAt)
        assertEquals(3000L, updated.readAt)
        assertNull(updated.playedAt)
        assertEquals(3050L, updated.updatedAt)
    }

    @Test
    fun `mixed advance and regression resolve independently per field`() {
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
            deliveredAt = 9000L,
            readAt = 1000L,
            playedAt = null,
            now = 9100L,
        )

        assertEquals(9000L, updated.deliveredAt)
        assertEquals(6000L, updated.readAt)
        assertEquals(7000L, updated.playedAt)
        assertEquals(9100L, updated.updatedAt)
    }

    @Test
    fun `equal timestamps merge without changing stored values`() {
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
            deliveredAt = 5000L,
            readAt = 6000L,
            playedAt = 7000L,
            now = 8000L,
        )

        assertEquals(5000L, updated.deliveredAt)
        assertEquals(6000L, updated.readAt)
        assertEquals(7000L, updated.playedAt)
        assertEquals(8000L, updated.updatedAt)
    }

    @Test
    fun `fresh receipt with only a played timestamp`() {
        val fresh = MessageReceiptAggregationPolicy.mergeReceipt(
            existing = null,
            ownerUserId = "alice",
            messageId = "msg-1",
            conversationId = "conv-1",
            recipientUserId = "bob",
            deliveredAt = null,
            readAt = null,
            playedAt = 4000L,
            now = 4050L,
        )

        assertNull(fresh.deliveredAt)
        assertNull(fresh.readAt)
        assertEquals(4000L, fresh.playedAt)
        assertEquals(4050L, fresh.updatedAt)
    }
}
