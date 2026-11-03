package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupChainCheckinPolicyTest {

    @Test
    fun `word chain formats and parses back`() {
        val formatted = GroupChainPolicy.formatWordChain("apple", "Alice")
        assertTrue(formatted.startsWith(GroupChainPolicy.WORD_PREFIX))
        val parsed = GroupChainPolicy.parseWordChain(formatted)
        assertEquals("apple", parsed?.first)
        assertTrue(parsed?.second?.contains("Alice") == true)
    }

    @Test
    fun `word chain rejects non prefixed content and blank seed`() {
        assertNull(GroupChainPolicy.parseWordChain("hello"))
        assertNull(GroupChainPolicy.parseWordChain("WORD:  |x"))
    }

    @Test
    fun `random word seed comes from the seed list`() {
        assertTrue(GroupChainPolicy.wordChainSeeds.contains(GroupChainPolicy.randomWordSeed()))
    }

    @Test
    fun `checkin formats and parses back`() {
        val formatted = GroupCheckinPolicy.formatCheckIn(5, "Bob")
        assertTrue(formatted.startsWith(GroupCheckinPolicy.CHECKIN_PREFIX))
        assertEquals(5 to "Bob checked in · streak 5", GroupCheckinPolicy.parseCheckIn(formatted))
    }

    @Test
    fun `checkin rejects non prefixed content and bad streak`() {
        assertNull(GroupCheckinPolicy.parseCheckIn("hello"))
        assertNull(GroupCheckinPolicy.parseCheckIn("CHECKIN:abc|Bob"))
    }
}
