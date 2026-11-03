package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupPkPolicyTest {

    @Test
    fun `rps format and parse round-trip`() {
        assertEquals("RPS:rock|Alice played [R] rock", GroupPkPolicy.formatRps("rock", "Alice"))
        assertEquals("rock", GroupPkPolicy.parseRps("RPS:rock|Alice played [R] rock"))
        assertNull(GroupPkPolicy.parseRps("hello"))
    }

    @Test
    fun `number bomb format and parse round-trip`() {
        assertEquals(
            "BOMB:100:42|Bob started number bomb (1-100)",
            GroupPkPolicy.formatNumberBomb(42, 100, "Bob")
        )
        assertEquals(
            Triple(100, 42, "Bob started number bomb (1-100)"),
            GroupPkPolicy.parseNumberBomb("BOMB:100:42|Bob started number bomb (1-100)")
        )
        assertNull(GroupPkPolicy.parseNumberBomb("BOMB:bad"))
    }

    @Test
    fun `reaction race format and parse round-trip`() {
        val formatted = GroupPkPolicy.formatReactionRace("🚀", "Carol")
        assertEquals("🚀" to "Carol reaction race: first to reply with 🚀 wins", GroupPkPolicy.parseReactionRace(formatted))
        assertNull(GroupPkPolicy.parseReactionRace("RACE:"))
    }

    @Test
    fun `number bomb roll stays within bounds`() {
        val (secret, hi) = GroupPkPolicy.rollNumberBomb(100)
        assertEquals(100, hi)
        assertTrue(secret in 1..hi)
    }
}
