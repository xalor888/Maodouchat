package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentContactQueryPolicyTest {

    @Test
    fun `normalizeQuery trims or empties`() {
        assertEquals("", AgentContactQueryPolicy.normalizeQuery(null))
        assertEquals("ab", AgentContactQueryPolicy.normalizeQuery("  ab "))
    }

    @Test
    fun `searchQuery truncates at 80`() {
        assertEquals("ab", AgentContactQueryPolicy.searchQuery("ab"))
        assertEquals("x".repeat(80), AgentContactQueryPolicy.searchQuery("x".repeat(100)))
    }

    @Test
    fun `displayName prefers non-blank nickname`() {
        assertEquals("Nick", AgentContactQueryPolicy.displayName("Nick", "Name"))
        assertEquals("Name", AgentContactQueryPolicy.displayName("  ", "Name"))
        assertEquals("Name", AgentContactQueryPolicy.displayName(null, "Name"))
    }

    @Test
    fun `isIncoming treats only outgoing as outgoing`() {
        assertTrue(AgentContactQueryPolicy.isIncoming("incoming"))
        assertTrue(AgentContactQueryPolicy.isIncoming(""))
        assertFalse(AgentContactQueryPolicy.isIncoming("outgoing"))
        assertFalse(AgentContactQueryPolicy.isIncoming("OUTGOING"))
    }

    @Test
    fun `userSearchLimit clamps to 1 to 30`() {
        assertEquals(1, AgentContactQueryPolicy.userSearchLimit(0))
        assertEquals(1, AgentContactQueryPolicy.userSearchLimit(1))
        assertEquals(30, AgentContactQueryPolicy.userSearchLimit(30))
        assertEquals(30, AgentContactQueryPolicy.userSearchLimit(31))
    }

    @Test
    fun `row limits stay 80 and 50`() {
        assertEquals(80, AgentContactQueryPolicy.MAX_CONTACT_ROWS)
        assertEquals(50, AgentContactQueryPolicy.MAX_FRIEND_REQUEST_ROWS)
    }
}
