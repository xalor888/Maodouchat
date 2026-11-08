package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageReceiptGuardTest {

    @Test
    fun `delivery receipt applies only to own message from a peer`() {
        assertTrue(MessageReceiptGuard.shouldApplyDeliveryReceipt("me", "me", "peer"))
        assertFalse(MessageReceiptGuard.shouldApplyDeliveryReceipt("peer", "me", "peer"))
        assertFalse(MessageReceiptGuard.shouldApplyDeliveryReceipt("me", "me", "me"))
        assertFalse(MessageReceiptGuard.shouldApplyDeliveryReceipt("peer", "me", "me"))
    }

    @Test
    fun `read receipt from self is the own read echo`() {
        assertTrue(MessageReceiptGuard.isOwnReadReceipt("me", "me"))
        assertFalse(MessageReceiptGuard.isOwnReadReceipt("peer", "me"))
    }

    @Test
    fun `peer read receipt applies only to own messages`() {
        assertTrue(MessageReceiptGuard.shouldApplyPeerReadReceipt("me", "me"))
        assertFalse(MessageReceiptGuard.shouldApplyPeerReadReceipt("peer", "me"))
    }

    @Test
    fun `play receipt applies only to own message from a peer`() {
        assertTrue(MessageReceiptGuard.shouldApplyPlayReceipt("me", "me", "peer"))
        assertFalse(MessageReceiptGuard.shouldApplyPlayReceipt("peer", "me", "peer"))
        assertFalse(MessageReceiptGuard.shouldApplyPlayReceipt("me", "me", "me"))
        assertFalse(MessageReceiptGuard.shouldApplyPlayReceipt("peer", "me", "me"))
    }
}
