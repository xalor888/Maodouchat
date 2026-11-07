package com.maodouchat.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatListReceiptPolicyTest {

    @Test
    fun outgoingTailShowsReceipt() {
        val receipt = ChatListReceiptPolicy.fromLatest(
            senderId = "me", status = MessageStatus.READ, isHidden = false,
            currentUserId = "me",
        )
        assertNotNull(receipt)
        assertTrue(receipt.fromMe)
        assertEquals(MessageStatus.READ, receipt.status)
    }

    @Test
    fun inboundTailHidesTicks() {
        assertNull(
            ChatListReceiptPolicy.fromLatest(
                senderId = "peer", status = MessageStatus.DELIVERED, isHidden = false,
                currentUserId = "me",
            )
        )
    }

    @Test
    fun blankOwnerOrNullMessageHidesTicks() {
        assertNull(ChatListReceiptPolicy.fromLatest(senderId = null, status = null, isHidden = false, currentUserId = "me"))
        assertNull(
            ChatListReceiptPolicy.fromLatest(
                senderId = "me", status = MessageStatus.SENT, isHidden = false,
                currentUserId = "",
            )
        )
    }

    @Test
    fun hiddenSenderKeyDoesNotShowTicks() {
        assertNull(
            ChatListReceiptPolicy.fromLatest(
                senderId = "me", status = MessageStatus.SENT, isHidden = true,
                currentUserId = "me",
            )
        )
    }

    @Test
    fun groupDeliveredShowsAsSentSingleCheck() {
        val receipt = ChatListReceiptPolicy.fromLatest(
            senderId = "me", status = MessageStatus.DELIVERED, isHidden = false,
            currentUserId = "me",
            isGroup = true,
        )
        assertNotNull(receipt)
        assertEquals(MessageStatus.SENT, receipt.status)
        assertEquals(MessageStatus.SENT, ChatListReceiptPolicy.displayStatus(MessageStatus.DELIVERED, isGroup = true))
        assertEquals(MessageStatus.READ, ChatListReceiptPolicy.displayStatus(MessageStatus.READ, isGroup = true))
        assertEquals(MessageStatus.DELIVERED, ChatListReceiptPolicy.displayStatus(MessageStatus.DELIVERED, isGroup = false))
    }

    @Test
    fun groupNonDeliveredStatusesPassThrough() {
        assertEquals(MessageStatus.SENT, ChatListReceiptPolicy.displayStatus(MessageStatus.SENT, isGroup = true))
        assertEquals(MessageStatus.FAILED, ChatListReceiptPolicy.displayStatus(MessageStatus.FAILED, isGroup = true))
        assertEquals(MessageStatus.SENDING, ChatListReceiptPolicy.displayStatus(MessageStatus.SENDING, isGroup = true))
        assertEquals(MessageStatus.READ, ChatListReceiptPolicy.displayStatus(MessageStatus.READ, isGroup = false))
    }

    @Test
    fun badgeTextBoundaries() {
        assertEquals("99", ChatListReceiptPolicy.unreadBadgeText(99, markedUnread = false))
        assertEquals("99+", ChatListReceiptPolicy.unreadBadgeText(100, markedUnread = false))
        assertEquals("1", ChatListReceiptPolicy.unreadBadgeText(1, markedUnread = false))
        assertEquals("", ChatListReceiptPolicy.unreadBadgeText(0, markedUnread = false))
        assertTrue(ChatListReceiptPolicy.showUnreadBadge(1, markedUnread = false))
        assertTrue(ChatListReceiptPolicy.showUnreadBadge(5, markedUnread = true))
        assertEquals("5", ChatListReceiptPolicy.unreadBadgeText(5, markedUnread = true))
    }

    @Test
    fun sendingTailShowsSendingReceipt() {
        val receipt = ChatListReceiptPolicy.fromLatest(
            senderId = "me", status = MessageStatus.SENDING, isHidden = false,
            currentUserId = "me",
        )
        assertNotNull(receipt)
        assertTrue(receipt.fromMe)
        assertEquals(MessageStatus.SENDING, receipt.status)
    }

    @Test
    fun groupReadKeepsDoubleCheck() {
        val readReceipt = ChatListReceiptPolicy.fromLatest(
            senderId = "me", status = MessageStatus.READ, isHidden = false,
            currentUserId = "me",
            isGroup = true,
        )
        assertNotNull(readReceipt)
        assertEquals(MessageStatus.READ, readReceipt.status)
        val sentReceipt = ChatListReceiptPolicy.fromLatest(
            senderId = "me", status = MessageStatus.SENT, isHidden = false,
            currentUserId = "me",
            isGroup = true,
        )
        assertNotNull(sentReceipt)
        assertEquals(MessageStatus.SENT, sentReceipt.status)
    }

    @Test
    fun whitespaceOwnerAndCaseSensitiveSenderHideTicks() {
        assertNull(
            ChatListReceiptPolicy.fromLatest(
                senderId = "me", status = MessageStatus.SENT, isHidden = false,
                currentUserId = " ",
            )
        )
        assertNull(
            ChatListReceiptPolicy.fromLatest(
                senderId = "ME", status = MessageStatus.SENT, isHidden = false,
                currentUserId = "me",
            )
        )
    }

    @Test
    fun failedTailKeepsFailedReceiptInBothChatKinds() {
        val direct = ChatListReceiptPolicy.fromLatest(
            senderId = "me", status = MessageStatus.FAILED, isHidden = false,
            currentUserId = "me",
        )
        assertNotNull(direct)
        assertTrue(direct.fromMe)
        assertEquals(MessageStatus.FAILED, direct.status)
        val group = ChatListReceiptPolicy.fromLatest(
            senderId = "me", status = MessageStatus.FAILED, isHidden = false,
            currentUserId = "me",
            isGroup = true,
        )
        assertNotNull(group)
        assertEquals(MessageStatus.FAILED, group.status)
    }

    @Test
    fun negativeUnreadCountsShowNoBadge() {
        assertEquals("", ChatListReceiptPolicy.unreadBadgeText(-5, markedUnread = false))
        assertFalse(ChatListReceiptPolicy.showUnreadBadge(-5, markedUnread = false))
        assertEquals("", ChatListReceiptPolicy.unreadBadgeText(-5, markedUnread = true))
        assertTrue(ChatListReceiptPolicy.showUnreadBadge(-5, markedUnread = true))
    }

    @Test
    fun nonGroupDisplayStatusPassesEveryStatusThrough() {
        MessageStatus.values().forEach { status ->
            assertEquals(status, ChatListReceiptPolicy.displayStatus(status, isGroup = false))
        }
    }

    @Test
    fun blankSenderHidesTicksWhileSystemTypeShowsReceipt() {
        assertNull(
            ChatListReceiptPolicy.fromLatest(
                senderId = "", status = MessageStatus.SENT, isHidden = false,
                currentUserId = "me",
            )
        )
        val system = ChatListReceiptPolicy.fromLatest(
            senderId = "me", status = MessageStatus.SENT, isHidden = false,
            currentUserId = "me",
        )
        assertNotNull(system)
        assertEquals(MessageStatus.SENT, system.status)
    }

    @Test
    fun markedUnreadWithHugeCountStillCapsAt99Plus() {
        assertEquals("99+", ChatListReceiptPolicy.unreadBadgeText(150, markedUnread = true))
        assertTrue(ChatListReceiptPolicy.showUnreadBadge(150, markedUnread = true))
    }

    @Test
    fun markedUnreadKeepsBadgeWithoutNumber() {
        assertTrue(ChatListReceiptPolicy.showUnreadBadge(0, markedUnread = true))
        assertEquals("", ChatListReceiptPolicy.unreadBadgeText(0, markedUnread = true))
        assertEquals("3", ChatListReceiptPolicy.unreadBadgeText(3, markedUnread = false))
        assertEquals("99+", ChatListReceiptPolicy.unreadBadgeText(120, markedUnread = false))
        assertFalse(ChatListReceiptPolicy.showUnreadBadge(0, markedUnread = false))
    }
}
