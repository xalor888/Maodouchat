package com.maodouchat.chatdetail

import com.maodouchat.ui.screen.chatdetail.GroupRevisionImpact
import com.maodouchat.ui.screen.chatdetail.groupRevisionImpact
import com.maodouchat.ui.screen.chatdetail.isActiveChatEvent
import com.maodouchat.ui.screen.chatdetail.shouldApplyContactPresence
import com.maodouchat.ui.screen.chatdetail.shouldInvalidateGroupKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRealtimeEventPolicyTest {
    @Test
    fun `strict chat events cannot cross chat boundaries`() {
        assertTrue(isActiveChatEvent("chat-a", "chat-a"))
        assertFalse(isActiveChatEvent("chat-a", "chat-b"))
        assertFalse(isActiveChatEvent("", ""))
    }

    @Test
    fun `presence only updates direct chat contact`() {
        assertTrue(shouldApplyContactPresence(false, "user-2", "user-2"))
        assertFalse(shouldApplyContactPresence(true, "user-2", "user-2"))
        assertFalse(shouldApplyContactPresence(false, "user-2", "user-3"))
    }

    @Test
    fun `current user removal is terminal even with equal revision`() {
        val removed = groupRevisionImpact("chat-a", "user-1", "chat-a", "user-1", "MEMBER_REMOVED")
        assertEquals(GroupRevisionImpact.CURRENT_USER_REMOVED, removed)
        assertTrue(shouldInvalidateGroupKey(5L, 5L, removed))
        assertEquals(
            GroupRevisionImpact.IGNORE,
            groupRevisionImpact("chat-a", "user-1", "chat-b", "user-1", "MEMBER_REMOVED")
        )
        assertTrue(shouldInvalidateGroupKey(5L, 6L, GroupRevisionImpact.REFRESH))
        assertFalse(shouldInvalidateGroupKey(6L, 5L, GroupRevisionImpact.REFRESH))
    }

    @Test
    fun `current user leaving is terminal like removal`() {
        val left = groupRevisionImpact("chat-a", "user-1", "chat-a", "user-1", "MEMBER_LEFT")
        assertEquals(GroupRevisionImpact.CURRENT_USER_REMOVED, left)
        assertTrue(shouldInvalidateGroupKey(7L, 7L, left))
    }

    @Test
    fun `other member removal is plain refresh not terminal`() {
        val impact = groupRevisionImpact("chat-a", "user-1", "chat-a", "user-2", "MEMBER_REMOVED")
        assertEquals(GroupRevisionImpact.REFRESH, impact)
        assertFalse(shouldInvalidateGroupKey(5L, 5L, impact))
        assertTrue(shouldInvalidateGroupKey(5L, 6L, impact))
    }

    @Test
    fun `non-removal event never marks current user removed`() {
        val impact = groupRevisionImpact("chat-a", "user-1", "chat-a", "user-1", "MEMBER_JOINED")
        assertEquals(GroupRevisionImpact.REFRESH, impact)
    }

    @Test
    fun `presence requires non-blank contact id`() {
        assertFalse(shouldApplyContactPresence(false, "", ""))
        assertFalse(shouldApplyContactPresence(false, "", "user-2"))
    }

    @Test
    fun `mismatched or blank event chat never counts as active`() {
        assertFalse(isActiveChatEvent("chat-a", ""))
        assertFalse(isActiveChatEvent("", "chat-a"))
    }

    @Test
    fun `blank current user id never counts as removed`() {
        assertEquals(
            GroupRevisionImpact.REFRESH,
            groupRevisionImpact("chat-a", "", "chat-a", "", "MEMBER_REMOVED")
        )
        assertFalse(shouldInvalidateGroupKey(5L, 5L, GroupRevisionImpact.REFRESH))
    }

    @Test
    fun `unknown removal reason is plain refresh`() {
        assertEquals(
            GroupRevisionImpact.REFRESH,
            groupRevisionImpact("chat-a", "user-1", "chat-a", "user-1", "MEMBER_BANNED")
        )
    }

    @Test
    fun `null target user never marks current user removed`() {
        assertEquals(
            GroupRevisionImpact.REFRESH,
            groupRevisionImpact("chat-a", "user-1", "chat-a", null, "MEMBER_REMOVED")
        )
    }

    @Test
    fun `removed impact invalidates even on stale revision`() {
        assertTrue(shouldInvalidateGroupKey(9L, 1L, GroupRevisionImpact.CURRENT_USER_REMOVED))
        assertTrue(shouldInvalidateGroupKey(5L, 6L, GroupRevisionImpact.IGNORE))
        assertFalse(shouldInvalidateGroupKey(6L, 5L, GroupRevisionImpact.IGNORE))
    }

    @Test
    fun `whitespace contact id never applies presence`() {
        assertFalse(shouldApplyContactPresence(false, " ", " "))
        assertFalse(shouldApplyContactPresence(false, "user-2", " "))
        assertFalse(shouldApplyContactPresence(false, " ", "user-2"))
    }

}
