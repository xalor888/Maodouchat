package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRevisionImpactModelsTest {

    @Test
    fun `events for another chat are ignored`() {
        assertEquals(
            GroupRevisionImpact.IGNORE,
            groupRevisionImpact("chat-a", "user-1", "chat-b", "user-1", "MEMBER_REMOVED")
        )
        assertFalse(isActiveChatEvent("chat-a", "chat-b"))
        assertFalse(isActiveChatEvent("", ""))
    }

    @Test
    fun `current user removal is terminal even at equal revision`() {
        val removed = groupRevisionImpact("chat-a", "user-1", "chat-a", "user-1", "MEMBER_REMOVED")
        assertEquals(GroupRevisionImpact.CURRENT_USER_REMOVED, removed)
        assertTrue(shouldInvalidateGroupKey(5L, 5L, removed))
        val left = groupRevisionImpact("chat-a", "user-1", "chat-a", "user-1", "MEMBER_LEFT")
        assertEquals(GroupRevisionImpact.CURRENT_USER_REMOVED, left)
    }

    @Test
    fun `other member removal is a plain refresh`() {
        val impact = groupRevisionImpact("chat-a", "user-1", "chat-a", "user-2", "MEMBER_REMOVED")
        assertEquals(GroupRevisionImpact.REFRESH, impact)
        assertFalse(shouldInvalidateGroupKey(5L, 5L, impact))
        assertTrue(shouldInvalidateGroupKey(5L, 6L, impact))
    }

    @Test
    fun `presence only applies to the direct chat contact`() {
        assertTrue(shouldApplyContactPresence(false, "user-2", "user-2"))
        assertFalse(shouldApplyContactPresence(true, "user-2", "user-2"))
        assertFalse(shouldApplyContactPresence(false, "user-2", "user-3"))
        assertFalse(shouldApplyContactPresence(false, "", "user-2"))
    }

    @Test
    fun `non-removal reasons never mark the current user removed`() {
        assertEquals(
            GroupRevisionImpact.REFRESH,
            groupRevisionImpact("chat-a", "user-1", "chat-a", "user-1", "MEMBER_JOINED")
        )
        assertEquals(
            GroupRevisionImpact.REFRESH,
            groupRevisionImpact("chat-a", "user-1", "chat-a", null, "MEMBER_REMOVED")
        )
    }
}
