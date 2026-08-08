package com.maodouchat.chatlist

import com.maodouchat.ui.screen.chatlist.ChatListUnreadPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatListUnreadPolicyTest {

    @Test
    fun activeChatAlwaysZeroUnread() {
        assertEquals(
            0,
            ChatListUnreadPolicy.mergeUnreadCount(
                serverUnread = 9,
                localUnread = 3,
                isActiveChat = true
            )
        )
    }

    @Test
    fun keepsLocalZeroWhenServerStaleTail() {
        assertEquals(
            0,
            ChatListUnreadPolicy.mergeUnreadCount(
                serverUnread = 5,
                localUnread = 0,
                isActiveChat = false,
                serverLastMessageTime = 100,
                localLastMessageTime = 100
            )
        )
        assertEquals(
            0,
            ChatListUnreadPolicy.mergeUnreadCount(
                serverUnread = 2,
                localUnread = 0,
                isActiveChat = false,
                serverLastMessageTime = 90,
                localLastMessageTime = 100
            )
        )
    }

    @Test
    fun emptyLocalTailDoesNotHideServerUnread() {
        // Stub/empty Room row (time 0) must not suppress server badge.
        assertEquals(
            4,
            ChatListUnreadPolicy.mergeUnreadCount(
                serverUnread = 4,
                localUnread = 0,
                isActiveChat = false,
                serverLastMessageTime = 100,
                localLastMessageTime = 0
            )
        )
    }

    @Test
    fun acceptsServerUnreadWhenTailIsNewer() {
        assertEquals(
            4,
            ChatListUnreadPolicy.mergeUnreadCount(
                serverUnread = 4,
                localUnread = 0,
                isActiveChat = false,
                serverLastMessageTime = 200,
                localLastMessageTime = 100
            )
        )
    }

    @Test
    fun prefersHigherWhenBothPositive() {
        assertEquals(
            7,
            ChatListUnreadPolicy.mergeUnreadCount(
                serverUnread = 7,
                localUnread = 3,
                isActiveChat = false,
                serverLastMessageTime = 200,
                localLastMessageTime = 100
            )
        )
        assertEquals(
            5,
            ChatListUnreadPolicy.mergeUnreadCount(
                serverUnread = 2,
                localUnread = 5,
                isActiveChat = false,
                serverLastMessageTime = 100,
                localLastMessageTime = 150
            )
        )
    }

    @Test
    fun noLocalUsesServer() {
        assertEquals(
            3,
            ChatListUnreadPolicy.mergeUnreadCount(
                serverUnread = 3,
                localUnread = null,
                isActiveChat = false
            )
        )
    }

    @Test
    fun markedUnreadMerge() {
        assertFalse(
            ChatListUnreadPolicy.mergeMarkedUnread(
                serverMarked = true,
                localMarked = false,
                isActiveChat = true
            )
        )
        assertTrue(
            ChatListUnreadPolicy.mergeMarkedUnread(
                serverMarked = false,
                localMarked = true,
                isActiveChat = false
            )
        )
        assertFalse(
            ChatListUnreadPolicy.mergeMarkedUnread(
                serverMarked = false,
                localMarked = false,
                isActiveChat = false
            )
        )
        assertTrue(
            ChatListUnreadPolicy.mergeMarkedUnread(
                serverMarked = true,
                localMarked = null,
                isActiveChat = false
            )
        )
    }
}
