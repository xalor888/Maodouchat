package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
