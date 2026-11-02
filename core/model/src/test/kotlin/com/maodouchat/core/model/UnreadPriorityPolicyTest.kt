package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnreadPriorityPolicyTest {

    @Test
    fun `muted chats sort by time only`() {
        assertEquals(
            9_000L,
            UnreadPriorityPolicy.activityScore(
                lastMessageTime = 9_000L, unreadCount = 50, markedUnread = true, muted = true
            )
        )
    }

    @Test
    fun `marked unread outweighs plain unread and unread count is capped`() {
        val marked = UnreadPriorityPolicy.activityScore(
            lastMessageTime = 1_000L, unreadCount = 0, markedUnread = true
        )
        val plain = UnreadPriorityPolicy.activityScore(
            lastMessageTime = 1_000L, unreadCount = 9999
        )
        assertTrue(marked > plain)
        assertEquals(
            UnreadPriorityPolicy.WEIGHT_HAS_UNREAD + 999,
            UnreadPriorityPolicy.activityScore(
                lastMessageTime = 0L, unreadCount = 9999
            ) - 0L
        )
    }

    @Test
    fun `hint shows only when enabled with unread and not searching`() {
        assertTrue(UnreadPriorityPolicy.shouldShowHint(true, 3, false))
        assertFalse(UnreadPriorityPolicy.shouldShowHint(false, 3, false))
        assertFalse(UnreadPriorityPolicy.shouldShowHint(true, 3, true))
        assertFalse(UnreadPriorityPolicy.shouldShowHint(true, 0, false))
        assertEquals(2, UnreadPriorityPolicy.countUnreadChats(listOf(0, 5), listOf(true, false)))
    }
}
