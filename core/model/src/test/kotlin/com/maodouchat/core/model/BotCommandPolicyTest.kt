package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotCommandPolicyTest {

    @Test
    fun `slash with target and args parses all three parts`() {
        val parsed = BotCommandPolicy.parseSlash("/start@helper_bot arg1 arg2")
        assertEquals("start", parsed?.command)
        assertEquals("helper_bot", parsed?.targetUsername)
        assertEquals("arg1 arg2", parsed?.arguments)
    }

    @Test
    fun `non-slash draft offers no composer menu`() {
        assertFalse(BotCommandPolicy.shouldOfferComposerMenu("help"))
        assertTrue(BotCommandPolicy.filterCommands(emptyList(), "help").isEmpty())
    }

    @Test
    fun `max text chars constant stays 4000`() {
        assertEquals(4_000, BotCommandPolicy.MAX_TEXT_CHARS)
    }

    @Test
    fun `at-bot mention without group bots never goes to inbox`() {
        assertFalse(BotCommandPolicy.shouldSendInbox("@helper_bot hi", false, false))
        assertTrue(BotCommandPolicy.shouldSendInbox("@helper_bot hi", false, true))
    }
}
