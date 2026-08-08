package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnreadPriorityPolicyTest {
    @Test
    fun `unread ranks above older read`() {
        val olderUnread = UnreadPriorityPolicy.activityScore(
            lastMessageTime = 1_000L,
            unreadCount = 2
        )
        val newerRead = UnreadPriorityPolicy.activityScore(
            lastMessageTime = 9_000L,
            unreadCount = 0
        )
        assertTrue(olderUnread > newerRead)
    }

    @Test
    fun `marked unread boosts`() {
        val marked = UnreadPriorityPolicy.activityScore(
            lastMessageTime = 100L,
            markedUnread = true
        )
        val plainUnread = UnreadPriorityPolicy.activityScore(
            lastMessageTime = 100L,
            unreadCount = 1
        )
        assertTrue(marked > plainUnread)
    }

    @Test
    fun `muted does not boost`() {
        val mutedUnread = UnreadPriorityPolicy.activityScore(
            lastMessageTime = 100L,
            unreadCount = 5,
            muted = true
        )
        val plain = UnreadPriorityPolicy.activityScore(
            lastMessageTime = 100L,
            unreadCount = 0,
            muted = false
        )
        assertEquals(plain, mutedUnread)
    }

    @Test
    fun `hint and count`() {
        assertEquals(2, UnreadPriorityPolicy.countUnreadChats(listOf(1, 0, 0), listOf(false, true, false)))
        assertTrue(UnreadPriorityPolicy.shouldShowHint(true, 1, isSearching = false))
        assertFalse(UnreadPriorityPolicy.shouldShowHint(true, 1, isSearching = true))
        assertFalse(UnreadPriorityPolicy.shouldShowHint(false, 3, isSearching = false))
        assertFalse(UnreadPriorityPolicy.shouldShowHint(true, 0, isSearching = false))
    }
}
