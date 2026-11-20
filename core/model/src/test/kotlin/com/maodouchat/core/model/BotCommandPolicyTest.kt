package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotCommandPolicyTest {

    @Test
    fun `non-slash draft offers no composer menu`() {
        assertFalse(BotCommandPolicy.shouldOfferComposerMenu("help"))
        assertTrue(BotCommandPolicy.filterCommands(emptyList(), "help").isEmpty())
    }

    @Test
    fun `max text chars constant stays 4000`() {
        assertEquals(4_000, BotCommandPolicy.MAX_TEXT_CHARS)
    }
}
