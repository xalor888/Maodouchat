package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentChatListPolicyTest {

    @Test
    fun `normalizeQuery trims and lowercases`() {
        assertEquals("", AgentChatListPolicy.normalizeQuery(null))
        assertEquals("", AgentChatListPolicy.normalizeQuery("   "))
        assertEquals("abc", AgentChatListPolicy.normalizeQuery("  AbC  "))
    }

    @Test
    fun `matchesQuery blank matches everything`() {
        assertTrue(AgentChatListPolicy.matchesQuery("Family", "hello", ""))
        assertTrue(AgentChatListPolicy.matchesQuery(null, "hello", ""))
    }

    @Test
    fun `matchesQuery checks group name and last message case-insensitively`() {
        assertTrue(AgentChatListPolicy.matchesQuery("Family", "bye", "fami"))
        assertTrue(AgentChatListPolicy.matchesQuery("Family", "Hello there", "hello"))
        assertFalse(AgentChatListPolicy.matchesQuery("Family", "bye", "zzz"))
    }

    @Test
    fun `listTitle prefers group name then first participant then id`() {
        assertEquals("Family", AgentChatListPolicy.listTitle("Family", "a,b", "c1"))
        assertEquals("a", AgentChatListPolicy.listTitle("  ", " ,a,b", "c1"))
        assertEquals("c1", AgentChatListPolicy.listTitle(null, " , ", "c1"))
        assertEquals("c1", AgentChatListPolicy.listTitle("", "", "c1"))
    }

    @Test
    fun `detailTitle falls back to raw participant ids`() {
        assertEquals("Family", AgentChatListPolicy.detailTitle("Family", "a,b"))
        assertEquals("a,b", AgentChatListPolicy.detailTitle(null, "a,b"))
        assertEquals("", AgentChatListPolicy.detailTitle("  ", ""))
    }

    @Test
    fun `chatFlags joins set flags in order`() {
        assertEquals("", AgentChatListPolicy.chatFlags(false, "DM", "c1", emptySet(), emptySet(), false, 0, false, 0))
        assertEquals(
            "group,channel,secret,pin,muted,pinned,archived,unread=3",
            AgentChatListPolicy.chatFlags(true, "CHANNEL", "c1", setOf("c1"), setOf("c1"), true, 5, true, 3),
        )
        assertEquals(
            "unread=1",
            AgentChatListPolicy.chatFlags(false, "DM", "c2", setOf("c1"), setOf("c1"), false, 0, false, 1),
        )
    }

    @Test
    fun `historyLimit clamps to 1 to 40`() {
        assertEquals(1, AgentChatListPolicy.historyLimit(0))
        assertEquals(1, AgentChatListPolicy.historyLimit(1))
        assertEquals(40, AgentChatListPolicy.historyLimit(40))
        assertEquals(40, AgentChatListPolicy.historyLimit(41))
    }
}
