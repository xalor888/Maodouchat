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
    fun `unread count is capped at 999`() {
        val huge = UnreadPriorityPolicy.activityScore(lastMessageTime = 100L, unreadCount = 5_000)
        val capped = UnreadPriorityPolicy.activityScore(lastMessageTime = 100L, unreadCount = 999)
        val single = UnreadPriorityPolicy.activityScore(lastMessageTime = 100L, unreadCount = 1)
        assertEquals(capped, huge)
        assertTrue(capped > single)
    }

    @Test
    fun `negative inputs are clamped`() {
        val negative = UnreadPriorityPolicy.activityScore(lastMessageTime = -50L, unreadCount = -3)
        val zero = UnreadPriorityPolicy.activityScore(lastMessageTime = 0L, unreadCount = 0)
        assertEquals(zero, negative)
    }

    @Test
    fun `draft activity wins when newer`() {
        val draftNewer = UnreadPriorityPolicy.activityScore(lastMessageTime = 100L, draftUpdatedAt = 900L)
        val messageNewer = UnreadPriorityPolicy.activityScore(lastMessageTime = 900L, draftUpdatedAt = 100L)
        assertEquals(messageNewer, draftNewer)
    }

    @Test
    fun `muted keeps time only even with marked unread`() {
        val mutedMarked = UnreadPriorityPolicy.activityScore(
            lastMessageTime = 100L,
            unreadCount = 5,
            markedUnread = true,
            muted = true
        )
        assertEquals(100L, mutedMarked)
    }

    @Test
    fun `countUnreadChats tolerates ragged lists`() {
        assertEquals(2, UnreadPriorityPolicy.countUnreadChats(listOf(1, 2), listOf(true)))
        assertEquals(2, UnreadPriorityPolicy.countUnreadChats(listOf(1), listOf(true, true)))
        assertEquals(0, UnreadPriorityPolicy.countUnreadChats(emptyList(), emptyList()))
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
