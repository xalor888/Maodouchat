package com.maodouchat.chatlist

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatlist.ChatListReceiptPolicy
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
            latest = message(senderId = "me", status = MessageStatus.READ),
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
                latest = message(senderId = "peer", status = MessageStatus.DELIVERED),
                currentUserId = "me",
            )
        )
    }

    @Test
    fun blankOwnerOrNullMessageHidesTicks() {
        assertNull(ChatListReceiptPolicy.fromLatest(latest = null, currentUserId = "me"))
        assertNull(
            ChatListReceiptPolicy.fromLatest(
                latest = message(senderId = "me", status = MessageStatus.SENT),
                currentUserId = "",
            )
        )
    }

    @Test
    fun hiddenSenderKeyDoesNotShowTicks() {
        assertNull(
            ChatListReceiptPolicy.fromLatest(
                latest = message(senderId = "me", type = MessageType.SK_DIST, status = MessageStatus.SENT),
                currentUserId = "me",
            )
        )
    }

    @Test
    fun groupDeliveredShowsAsSentSingleCheck() {
        val receipt = ChatListReceiptPolicy.fromLatest(
            latest = message(senderId = "me", status = MessageStatus.DELIVERED),
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
    fun markedUnreadKeepsBadgeWithoutNumber() {
        assertTrue(ChatListReceiptPolicy.showUnreadBadge(0, markedUnread = true))
        assertEquals("", ChatListReceiptPolicy.unreadBadgeText(0, markedUnread = true))
        assertEquals("3", ChatListReceiptPolicy.unreadBadgeText(3, markedUnread = false))
        assertEquals("99+", ChatListReceiptPolicy.unreadBadgeText(120, markedUnread = false))
        assertFalse(ChatListReceiptPolicy.showUnreadBadge(0, markedUnread = false))
    }

    private fun message(
        senderId: String,
        status: MessageStatus,
        type: MessageType = MessageType.TEXT,
    ) = Message(
        id = "m1",
        chatId = "c1",
        senderId = senderId,
        content = "hi",
        type = type,
        status = status,
    )
}
