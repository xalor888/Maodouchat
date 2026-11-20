package com.maodouchat.bot

import com.maodouchat.core.model.BotCommandItem
import com.maodouchat.core.model.BotCommandPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotCommandPolicyTest {

    @Test
    fun slashMenuFiltersAndInserts() {
        val items = listOf(
            BotCommandItem("bot_1", "helper_bot", "Helper", "help", "帮助"),
            BotCommandItem("bot_1", "helper_bot", "Helper", "ping", "探活"),
        )
        assertEquals(1, BotCommandPolicy.filterCommands(items, "/he").size)
        assertEquals("/help@helper_bot ", BotCommandPolicy.insertCommand(items[0], multiBot = true))
        assertEquals("/help ", BotCommandPolicy.insertCommand(items[0], multiBot = false))
        assertTrue(BotCommandPolicy.isBotUserId("bot_abc"))
        assertFalse(BotCommandPolicy.isBotUserId("u1"))
    }

    @Test
    fun composerMenuQueryNormalizes() {
        assertEquals("he", BotCommandPolicy.composerMenuQuery("/HE"))
        assertEquals("help", BotCommandPolicy.composerMenuQuery("/help@helper_bot"))
        assertEquals("help", BotCommandPolicy.composerMenuQuery("/help arg1"))
        assertFalse(BotCommandPolicy.shouldOfferComposerMenu("/a\nb"))
        assertFalse(BotCommandPolicy.shouldOfferComposerMenu("/" + "x".repeat(64)))
        assertTrue(BotCommandPolicy.shouldOfferComposerMenu("/" + "x".repeat(63)))
    }

    @Test
    fun filterCommandsCapsAtTwenty() {
        val items = (0 until 25).map {
            BotCommandItem("bot_1", "helper_bot", "Helper", "c%02d".format(it), "命令$it")
        }
        assertEquals(20, BotCommandPolicy.filterCommands(items, "/").size)
        assertTrue(BotCommandPolicy.filterCommands(items, "/zzz").isEmpty())
        assertTrue(BotCommandPolicy.filterCommands(items, "/c0\nx").isEmpty())
    }

    @Test
    fun composerMenuQueryAndOfferEdgeCases() {
        assertEquals("help", BotCommandPolicy.composerMenuQuery("  /HELP"))
        assertEquals("help", BotCommandPolicy.composerMenuQuery("/HELP@Helper_Bot arg"))
        assertTrue(BotCommandPolicy.shouldOfferComposerMenu("/"))
        assertTrue(BotCommandPolicy.shouldOfferComposerMenu(" /help"))
        assertFalse(BotCommandPolicy.shouldOfferComposerMenu(""))
        assertFalse(BotCommandPolicy.shouldOfferComposerMenu("/help\n"))
        assertTrue(BotCommandPolicy.shouldOfferComposerMenu("/" + "x".repeat(63)))
    }

    @Test
    fun filterCommandsMatchesCaseInsensitivelyAndPreservesOrder() {
        val items = listOf(
            BotCommandItem("bot_1", "helper_bot", "Helper", "help", "帮助"),
            BotCommandItem("bot_1", "helper_bot", "Helper", "ping", "探活"),
        )
        assertEquals(1, BotCommandPolicy.filterCommands(items, "/HE").size)
        assertTrue(BotCommandPolicy.filterCommands(items, "help").isEmpty())
        assertEquals(
            listOf("help", "ping"),
            BotCommandPolicy.filterCommands(items, "/").map { it.command },
        )
        assertTrue(BotCommandPolicy.isBotUserId("bot_"))
        assertFalse(BotCommandPolicy.isBotUserId("Bot_abc"))
        assertFalse(BotCommandPolicy.isBotUserId(""))
    }

}
