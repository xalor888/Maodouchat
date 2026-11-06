package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMembershipModelsTest {

    @Test
    fun `displayName prefers a non-blank group nickname`() {
        val member = GroupMemberUi(userId = "u1", name = "Alice", groupNickname = "Al")
        assertEquals("Al", member.displayName)
    }

    @Test
    fun `displayName falls back to name when nickname is blank`() {
        val member = GroupMemberUi(userId = "u1", name = "Alice", groupNickname = "  ")
        assertEquals("Alice", member.displayName)
    }

    @Test
    fun `isMuted follows mutedUntil against now`() {
        assertTrue(GroupMemberUi(userId = "u1", name = "A", mutedUntil = Long.MAX_VALUE).isMuted)
        assertFalse(GroupMemberUi(userId = "u1", name = "A", mutedUntil = 0L).isMuted)
    }

    @Test
    fun `toUi maps every GroupMemberDto field`() {
        val dto = GroupMemberDto(
            userId = "u1", name = "Alice", avatar = "a.png", role = "ADMIN",
            title = "Owner", groupNickname = "Al", joinedAt = 42L,
            isOnline = true, mutedUntil = 99L,
        )
        val ui = dto.toUi()
        assertEquals("u1", ui.userId)
        assertEquals("Alice", ui.name)
        assertEquals("a.png", ui.avatar)
        assertEquals("ADMIN", ui.role)
        assertEquals("Owner", ui.title)
        assertEquals("Al", ui.groupNickname)
        assertEquals(42L, ui.joinedAt)
        assertTrue(ui.isOnline)
        assertEquals(99L, ui.mutedUntil)
        assertEquals("Al", ui.displayName)
    }

    @Test
    fun `snapshot defaults to an empty member list`() {
        val snapshot = GroupMembershipSnapshot(chatId = "c1", memberRevision = 3L, members = emptyList())
        assertEquals("c1", snapshot.chatId)
        assertEquals(3L, snapshot.memberRevision)
        assertEquals("MEMBER", snapshot.myRole)
        assertTrue(snapshot.members.isEmpty())
    }
}
