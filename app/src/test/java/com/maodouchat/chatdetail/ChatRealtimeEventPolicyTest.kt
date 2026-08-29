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
}
