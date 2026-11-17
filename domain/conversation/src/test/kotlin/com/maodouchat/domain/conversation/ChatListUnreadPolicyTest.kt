package com.maodouchat.domain.conversation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatListUnreadPolicyTest {

    @Test
    fun `active chat always reports zero unread`() {
        assertEquals(
            0,
            ChatListUnreadPolicy.mergeUnreadCount(
                serverUnread = 9,
                localUnread = 3,
                isActiveChat = true
            )
        )
        assertFalse(
            ChatListUnreadPolicy.mergeMarkedUnread(
                serverMarked = true,
                localMarked = true,
                isActiveChat = true
            )
        )
    }

    @Test
    fun `local zero survives a stale server tail`() {
        assertEquals(
            0,
            ChatListUnreadPolicy.mergeUnreadCount(
                serverUnread = 5,
                localUnread = 0,
                isActiveChat = false,
                serverLastMessageTime = 100L,
                localLastMessageTime = 200L
            )
        )
    }

    @Test
    fun `manually marked unread is not wiped by a stale zero`() {
        assertEquals(
            5,
            ChatListUnreadPolicy.mergeUnreadCount(
                serverUnread = 5,
                localUnread = 0,
                isActiveChat = false,
                localMarkedUnread = true,
                serverLastMessageTime = 100L,
                localLastMessageTime = 200L
            )
        )
        assertTrue(
            ChatListUnreadPolicy.mergeMarkedUnread(
                serverMarked = false,
                localMarked = true,
                isActiveChat = false
            )
        )
    }

    @Test
    fun `no local state falls back to the server count`() {
        assertEquals(
            7,
            ChatListUnreadPolicy.mergeUnreadCount(
                serverUnread = 7,
                localUnread = null,
                isActiveChat = false
            )
        )
    }
}
