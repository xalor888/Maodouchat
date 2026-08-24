package com.maodouchat.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotCommandPolicyTest {

    @Test
    fun groupCiphertextNeverGoesToInbox() {
        assertFalse(
            BotCommandPolicy.shouldSendInbox(
                text = """{"ciphertext":"abc"}""",
                isDirectWithBot = false,
                hasGroupBots = true,
            )
        )
        assertFalse(
            BotCommandPolicy.shouldSendInbox(
                text = "hello",
                isDirectWithBot = false,
                hasGroupBots = true,
            )
        )
        assertTrue(
            BotCommandPolicy.shouldSendInbox(
                text = "/help",
                isDirectWithBot = false,
                hasGroupBots = true,
            )
        )
        assertTrue(
            BotCommandPolicy.shouldSendInbox(
                text = "hello",
                isDirectWithBot = true,
                hasGroupBots = false,
            )
        )
    }

    @Test
    fun slashMenuFiltersAndInserts() {
        val items = listOf(
            BotCommandPolicy.BotCommandItem("bot_1", "helper_bot", "Helper", "help", "帮助"),
            BotCommandPolicy.BotCommandItem("bot_1", "helper_bot", "Helper", "ping", "探活"),
        )
        assertEquals(1, BotCommandPolicy.filterCommands(items, "/he").size)
        assertEquals("/help@helper_bot ", BotCommandPolicy.insertCommand(items[0], multiBot = true))
        assertEquals("/help ", BotCommandPolicy.insertCommand(items[0], multiBot = false))
        assertTrue(BotCommandPolicy.isBotUserId("bot_abc"))
        assertFalse(BotCommandPolicy.isBotUserId("u1"))
    }
}
