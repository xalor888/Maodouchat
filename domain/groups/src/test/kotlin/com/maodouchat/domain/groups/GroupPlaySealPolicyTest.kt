package com.maodouchat.domain.groups

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class GroupPlaySealPolicyTest {

    @Test
    fun `link lock formats and parses back`() {
        val formatted = GroupPlaySealPolicy.formatLinkLock("strict", "Alice")
        assertEquals("LINKLOCK:strict|Alice link lock", formatted)
        assertEquals("strict", GroupPlaySealPolicy.parseLinkLock(formatted))
    }

    @Test
    fun `link lock rejects foreign prefix and blank mode`() {
        assertNull(GroupPlaySealPolicy.parseLinkLock("hello"))
        assertNull(GroupPlaySealPolicy.parseLinkLock(GroupPlaySealPolicy.formatLinkLock("", "Bob")))
    }

    @Test
    fun `separator chars in mode survive escaping round trip`() {
        val formatted = GroupPlaySealPolicy.formatLinkLock("a|b^c", "Alice")
        assertEquals("a|b^c", GroupPlaySealPolicy.parseLinkLock(formatted))
    }

    @Test
    fun `mode is trimmed and capped at forty chars`() {
        val formatted = GroupPlaySealPolicy.formatLinkLock("  " + "x".repeat(50) + "  ", "Alice")
        assertEquals("x".repeat(40), GroupPlaySealPolicy.parseLinkLock(formatted))
    }

    @Test
    fun `last seen seal formats and parses back`() {
        val formatted = GroupPlaySealPolicy.formatLastSeenSeal("week", "Carol")
        assertEquals("LASTSEENSEAL:week|Carol last seen seal", formatted)
        assertEquals("week", GroupPlaySealPolicy.parseLastSeenSeal(formatted))
    }
}
