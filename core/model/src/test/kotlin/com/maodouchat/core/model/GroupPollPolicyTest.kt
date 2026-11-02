package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupPollPolicyTest {

    @Test
    fun `pk shortcut normalizes invalid choice to left and round-trips`() {
        assertEquals("~pk:pk_1|left", GroupPollPolicy.buildPkShortcut("pk_1", "middle"))
        assertEquals("pk_1" to "right", GroupPollPolicy.parsePkShortcut("~pk:pk_1|right"))
        assertTrue(GroupPollPolicy.isPkShortcut("~pk:pk_1|left"))
        assertFalse(GroupPollPolicy.isPkShortcut("hello"))
    }

    @Test
    fun `vote shortcut round-trips and rejects blank poll id`() {
        assertEquals(
            "pk_9" to listOf(0, 2),
            GroupPollPolicy.parseVoteShortcut(GroupPollPolicy.buildVoteShortcut("pk_9", listOf(0, 2)))
        )
        assertEquals(null, GroupPollPolicy.parseVoteShortcut("~vote:|0"))
    }

    @Test
    fun `poll options reject duplicates blanks and wrong counts`() {
        assertTrue(GroupPollPolicy.isValidPollOptions(listOf("A", "B")))
        assertFalse(GroupPollPolicy.isValidPollOptions(listOf("A", "a")))
        assertFalse(GroupPollPolicy.isValidPollOptions(listOf("only")))
        assertFalse(GroupPollPolicy.isValidPollOptions(listOf(" ", "B")))
    }
}
