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

    @Test
    fun `score arithmetic is exact`() {
        assertEquals(
            1_000L + 500_000_000L + 3L,
            UnreadPriorityPolicy.activityScore(lastMessageTime = 1_000L, unreadCount = 3)
        )
        assertEquals(
            100L + 1_000_000_000L + 500_000_000L + 2L,
            UnreadPriorityPolicy.activityScore(lastMessageTime = 100L, unreadCount = 2, markedUnread = true)
        )
    }

    @Test
    fun `muted clamps negative time to zero`() {
        assertEquals(
            0L,
            UnreadPriorityPolicy.activityScore(
                lastMessageTime = -50L, unreadCount = 9, markedUnread = true, muted = true
            )
        )
    }

    @Test
    fun `message time wins when draft is older exact value`() {
        assertEquals(900L, UnreadPriorityPolicy.activityScore(lastMessageTime = 900L, draftUpdatedAt = 100L))
        assertEquals(900L, UnreadPriorityPolicy.activityScore(lastMessageTime = 100L, draftUpdatedAt = 900L))
    }

    @Test
    fun `countUnreadChats ignores non-positive counts`() {
        assertEquals(1, UnreadPriorityPolicy.countUnreadChats(listOf(-1, 0, 3), listOf(false, false, false)))
        assertEquals(0, UnreadPriorityPolicy.countUnreadChats(listOf(-5), listOf(false)))
    }

    @Test
    fun `single unread adds base weight plus one`() {
        assertEquals(500_000_001L, UnreadPriorityPolicy.activityScore(lastMessageTime = 0L, unreadCount = 1))
    }

    @Test
    fun `marked unread alone adds exactly its weight`() {
        assertEquals(1_000_000_100L, UnreadPriorityPolicy.activityScore(
            lastMessageTime = 100L, markedUnread = true))
        assertEquals(1_000_000_000L, UnreadPriorityPolicy.activityScore(
            lastMessageTime = 0L, markedUnread = true))
    }

    @Test
    fun `unread cap boundary 999 vs 1000 vs 998`() {
        val capped = UnreadPriorityPolicy.activityScore(lastMessageTime = 100L, unreadCount = 999)
        assertEquals(500_001_099L, capped)
        assertEquals(capped, UnreadPriorityPolicy.activityScore(
            lastMessageTime = 100L, unreadCount = 1000))
        assertEquals(capped - 1, UnreadPriorityPolicy.activityScore(
            lastMessageTime = 100L, unreadCount = 998))
    }

    @Test
    fun `muted keeps draft time even with unread and marked`() {
        assertEquals(900L, UnreadPriorityPolicy.activityScore(
            lastMessageTime = 100L, draftUpdatedAt = 900L,
            unreadCount = 5, markedUnread = true, muted = true))
    }

    @Test
    fun `countUnreadChats counts marked-only chats`() {
        assertEquals(2, UnreadPriorityPolicy.countUnreadChats(
            listOf(0, 0, 0), listOf(true, false, true)))
        assertEquals(2, UnreadPriorityPolicy.countUnreadChats(
            emptyList(), listOf(true, true)))
    }

    @Test
    fun `draft time exact with no unread weight`() {
        assertEquals(700L, UnreadPriorityPolicy.activityScore(
            lastMessageTime = 0L, draftUpdatedAt = 700L))
        assertEquals(100L, UnreadPriorityPolicy.activityScore(
            lastMessageTime = 100L, draftUpdatedAt = -5L))
    }


    @Test
    fun `hint rejects negative total`() {
        assertFalse(UnreadPriorityPolicy.shouldShowHint(true, -2, isSearching = false))
    }

    @Test
    fun `countUnreadChats counts dual-signal chat once`() {
        assertEquals(1, UnreadPriorityPolicy.countUnreadChats(listOf(3), listOf(true)))
    }

    @Test
    fun `marked unread outranks capped unread count`() {
        val marked = UnreadPriorityPolicy.activityScore(lastMessageTime = 100L, markedUnread = true)
        val capped = UnreadPriorityPolicy.activityScore(lastMessageTime = 100L, unreadCount = 999)
        assertEquals(1_000_000_100L, marked)
        assertEquals(500_001_099L, capped)
        assertTrue(marked > capped)
    }

    @Test
    fun `draft plus unread weights combine exactly`() {
        assertEquals(
            500_000_903L,
            UnreadPriorityPolicy.activityScore(
                lastMessageTime = 100L, draftUpdatedAt = 900L, unreadCount = 3
            )
        )
    }

    @Test
    fun `marked plus capped unread combine exactly`() {
        assertEquals(
            1_500_001_099L,
            UnreadPriorityPolicy.activityScore(
                lastMessageTime = 100L, unreadCount = 5000, markedUnread = true
            )
        )
    }

}
