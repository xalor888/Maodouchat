package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupPollPolicyTest {
    @Test
    fun pkShortcutNormalizesChoiceAndFallsBackForInvalidChoice() {
        assertEquals("~pk:pk_1|left", GroupPollPolicy.buildPkShortcut("pk_1", "left"))
        assertEquals("~pk:pk_1|right", GroupPollPolicy.buildPkShortcut("pk_1", "RIGHT"))
        assertEquals("~pk:pk_1|left", GroupPollPolicy.buildPkShortcut("pk_1", "middle"))
    }

    @Test
    fun pkShortcutParsesOnlyKnownChoices() {
        assertEquals("pk_1" to "left", GroupPollPolicy.parsePkShortcut("~pk:pk_1|left"))
        assertEquals("pk_1" to "right", GroupPollPolicy.parsePkShortcut("~pk:pk_1|right"))
        assertNull(GroupPollPolicy.parsePkShortcut("~pk:pk_1|middle"))
    }

    @Test
    fun voteShortcutRoundTrips() {
        val built = GroupPollPolicy.buildVoteShortcut("p1", listOf(0, 2))
        assertEquals("p1" to listOf(0, 2), GroupPollPolicy.parseVoteShortcut(built))
    }

    @Test
    fun voteShortcutRejectsMalformed() {
        assertNull(GroupPollPolicy.parseVoteShortcut("hello"))
        assertNull(GroupPollPolicy.parseVoteShortcut("~vote:|0"))
        assertNull(GroupPollPolicy.parseVoteShortcut("~vote:p1|"))
        assertNull(GroupPollPolicy.parseVoteShortcut("~vote:p1|abc"))
        assertFalse(GroupPollPolicy.isVoteShortcut("~vote:p1|"))
    }

    @Test
    fun checkinShortcutWithAndWithoutChatId() {
        assertEquals("g1", GroupPollPolicy.parseCheckinShortcut(GroupPollPolicy.buildCheckinShortcut("g1")))
        assertTrue(GroupPollPolicy.isCheckinShortcut(GroupPollPolicy.buildCheckinShortcut()))
        assertNull(GroupPollPolicy.parseCheckinShortcut("~checkin"))
    }

    @Test
    fun chainShortcutEscapesSeparators() {
        val built = GroupPollPolicy.buildChainShortcut("c1", "a|b^c")
        assertEquals("c1" to "a|b^c", GroupPollPolicy.parseChainShortcut(built))
    }

    @Test
    fun pollValidationBounds() {
        assertTrue(GroupPollPolicy.isValidPollQuestion("x".repeat(200)))
        assertFalse(GroupPollPolicy.isValidPollQuestion("x".repeat(201)))
        assertTrue(GroupPollPolicy.isValidPollOptions(listOf("a", "b")))
        assertFalse(GroupPollPolicy.isValidPollOptions(listOf("a")))
        assertFalse(GroupPollPolicy.isValidPollOptions(listOf("a", "A")))
    }
}
