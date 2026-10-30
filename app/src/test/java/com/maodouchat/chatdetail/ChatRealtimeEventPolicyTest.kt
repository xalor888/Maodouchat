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

    @Test
    fun `whitespace ids never count as active`() {
        assertFalse(isActiveChatEvent(" ", " "))
        assertFalse(isActiveChatEvent("chat-a", "chat-a "))
    }

    @Test
    fun `active chat match is case sensitive`() {
        assertFalse(isActiveChatEvent("Chat-A", "chat-a"))
        assertFalse(isActiveChatEvent("chat-a", "Chat-A"))
    }

    @Test
    fun `other member leaving is plain refresh not terminal`() {
        assertEquals(
            GroupRevisionImpact.REFRESH,
            groupRevisionImpact("chat-a", "user-1", "chat-a", "user-2", "MEMBER_LEFT")
        )
        assertEquals(
            GroupRevisionImpact.IGNORE,
            groupRevisionImpact("chat-a", "user-1", "chat-b", "user-2", "MEMBER_LEFT")
        )
    }

    @Test
    fun `lowercase removal reason is not terminal`() {
        assertEquals(
            GroupRevisionImpact.REFRESH,
            groupRevisionImpact("chat-a", "user-1", "chat-a", "user-1", "member_removed")
        )
        assertEquals(
            GroupRevisionImpact.REFRESH,
            groupRevisionImpact("chat-a", "user-1", "chat-a", "user-1", "Member_Removed")
        )
    }

    @Test
    fun `current user removal needs an active chat and ignore needs a newer revision`() {
        assertEquals(
            GroupRevisionImpact.IGNORE,
            groupRevisionImpact("", "user-1", "", "user-1", "MEMBER_REMOVED")
        )
        assertFalse(shouldInvalidateGroupKey(5L, 5L, GroupRevisionImpact.IGNORE))
    }


    @Test
    fun `joined event in another chat is ignored even for current user`() {
        assertEquals(
            GroupRevisionImpact.IGNORE,
            groupRevisionImpact("chat-a", "user-1", "chat-b", "user-1", "MEMBER_JOINED")
        )
    }

    @Test
    fun `blank target user with removal reason is plain refresh`() {
        assertEquals(
            GroupRevisionImpact.REFRESH,
            groupRevisionImpact("chat-a", "user-1", "chat-a", "", "MEMBER_REMOVED")
        )
    }

    @Test
    fun `padded active chat id mismatch is ignored`() {
        assertEquals(
            GroupRevisionImpact.IGNORE,
            groupRevisionImpact("chat-a ", "user-1", "chat-a", "user-1", "MEMBER_REMOVED")
        )
    }

    @Test
    fun `refresh invalidation is strict at revision boundaries`() {
        assertTrue(shouldInvalidateGroupKey(Long.MAX_VALUE - 1, Long.MAX_VALUE, GroupRevisionImpact.REFRESH))
        assertFalse(shouldInvalidateGroupKey(Long.MAX_VALUE, Long.MAX_VALUE, GroupRevisionImpact.REFRESH))
    }

    @Test
    fun `member left with blank active chat is ignored`() {
        assertEquals(
            GroupRevisionImpact.IGNORE,
            groupRevisionImpact("", "user-1", "", "user-1", "MEMBER_LEFT")
        )
    }


    @Test
    fun `member kicked reason is plain refresh`() {
        assertEquals(
            GroupRevisionImpact.REFRESH,
            groupRevisionImpact("chat-a", "user-1", "chat-a", "user-1", "MEMBER_KICKED")
        )
    }

    @Test
    fun `null target with joined reason is plain refresh`() {
        assertEquals(
            GroupRevisionImpact.REFRESH,
            groupRevisionImpact("chat-a", "user-1", "chat-a", null, "MEMBER_JOINED")
        )
    }

    @Test
    fun `left impact invalidates even on stale revision`() {
        val left = groupRevisionImpact("chat-a", "user-1", "chat-a", "user-1", "MEMBER_LEFT")
        assertEquals(GroupRevisionImpact.CURRENT_USER_REMOVED, left)
        assertTrue(shouldInvalidateGroupKey(9L, 1L, left))
    }

    @Test
    fun `presence contact id match is case sensitive`() {
        assertFalse(shouldApplyContactPresence(false, "User-2", "user-2"))
    }

    @Test
    fun `refresh invalidation works with negative revisions`() {
        assertTrue(shouldInvalidateGroupKey(-5L, -4L, GroupRevisionImpact.REFRESH))
        assertFalse(shouldInvalidateGroupKey(-4L, -5L, GroupRevisionImpact.REFRESH))
    }

    @Test
    fun `presence padded event id never matches`() {
        assertFalse(shouldApplyContactPresence(false, "user-2", "user-2 "))
        assertFalse(shouldApplyContactPresence(false, " user-2", "user-2"))
    }

    @Test
    fun `member left in another chat is ignored even for current user`() {
        assertEquals(
            GroupRevisionImpact.IGNORE,
            groupRevisionImpact("chat-a", "user-1", "chat-b", "user-1", "MEMBER_LEFT")
        )
    }

    @Test
    fun `null target with left reason is plain refresh`() {
        assertEquals(
            GroupRevisionImpact.REFRESH,
            groupRevisionImpact("chat-a", "user-1", "chat-a", null, "MEMBER_LEFT")
        )
    }

    @Test
    fun `empty reason never marks current user removed`() {
        assertEquals(
            GroupRevisionImpact.REFRESH,
            groupRevisionImpact("chat-a", "user-1", "chat-a", "user-1", "")
        )
    }

    @Test
    fun `refresh invalidation is false at equal zero revisions`() {
        assertFalse(shouldInvalidateGroupKey(0L, 0L, GroupRevisionImpact.REFRESH))
        assertTrue(shouldInvalidateGroupKey(0L, 1L, GroupRevisionImpact.REFRESH))
    }
}
