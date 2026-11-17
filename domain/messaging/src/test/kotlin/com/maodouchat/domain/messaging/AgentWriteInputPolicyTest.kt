package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AgentWriteInputPolicyTest {

    @Test
    fun `cleanSendText trims and truncates at 4000`() {
        assertEquals("hi", AgentWriteInputPolicy.cleanSendText("  hi\n"))
        assertEquals("x".repeat(4_000), AgentWriteInputPolicy.cleanSendText("x".repeat(4_500)))
    }

    @Test
    fun `cleanDraftText trims and truncates at 4000`() {
        assertEquals("draft", AgentWriteInputPolicy.cleanDraftText(" draft "))
        assertEquals("x".repeat(4_000), AgentWriteInputPolicy.cleanDraftText("x".repeat(4_100)))
    }

    @Test
    fun `cleanNickname trims and truncates at 40`() {
        assertEquals("bob", AgentWriteInputPolicy.cleanNickname(" bob "))
        assertEquals("x".repeat(40), AgentWriteInputPolicy.cleanNickname("x".repeat(60)))
    }

    @Test
    fun `parseMemberIds splits trims drops blanks and dedups`() {
        assertEquals(listOf("a", "b", "c", "d", "e"), AgentWriteInputPolicy.parseMemberIds("a, b;c d\ne, a"))
        assertEquals(listOf("a", "b"), AgentWriteInputPolicy.parseMemberIds("a,,  ,b"))
        assertEquals(emptyList<String>(), AgentWriteInputPolicy.parseMemberIds(" , ; \n "))
    }

    @Test
    fun `clampMuteUntil floors at zero`() {
        assertEquals(0L, AgentWriteInputPolicy.clampMuteUntil(-5L))
        assertEquals(0L, AgentWriteInputPolicy.clampMuteUntil(0L))
        assertEquals(1_700_000_000_000L, AgentWriteInputPolicy.clampMuteUntil(1_700_000_000_000L))
    }

    @Test
    fun `resolvePostVisibility normalizes case and rejects unknown values`() {
        assertNull(AgentWriteInputPolicy.resolvePostVisibility(null))
        assertNull(AgentWriteInputPolicy.resolvePostVisibility(""))
        assertNull(AgentWriteInputPolicy.resolvePostVisibility("bogus"))
        assertEquals("PUBLIC", AgentWriteInputPolicy.resolvePostVisibility(" public "))
        assertEquals("CONTACTS", AgentWriteInputPolicy.resolvePostVisibility("contacts"))
        assertEquals("PRIVATE", AgentWriteInputPolicy.resolvePostVisibility("PRIVATE"))
    }
}
