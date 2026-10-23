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
    fun parseSlashExtractsCommandTargetAndArguments() {
        val bare = BotCommandPolicy.parseSlash("/help")
        assertEquals("help", bare?.command)
        assertEquals(null, bare?.targetUsername)
        assertEquals("", bare?.arguments)

        val targeted = BotCommandPolicy.parseSlash("/start@helper_bot")
        assertEquals("start", targeted?.command)
        assertEquals("helper_bot", targeted?.targetUsername)
        assertEquals("", targeted?.arguments)

        val withArgs = BotCommandPolicy.parseSlash("/start arg1 arg2")
        assertEquals("start", withArgs?.command)
        assertEquals("arg1 arg2", withArgs?.arguments)

        assertEquals(null, BotCommandPolicy.parseSlash("hello"))
        assertEquals(null, BotCommandPolicy.parseSlash("/"))
    }

    @Test
    fun ciphertextPrefixesNeverGoToInbox() {
        assertFalse(BotCommandPolicy.shouldSendInbox("SK:abc", true, false))
        assertFalse(BotCommandPolicy.shouldSendInbox("ENC:xyz", false, true))
        assertFalse(BotCommandPolicy.shouldSendInbox("""{"senderKey":"k"}""", false, true))
        assertTrue(BotCommandPolicy.shouldSendInbox("  /help  ", false, true))
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
    fun atMentionOnlyValidBotShapesGoToInbox() {
        assertTrue(BotCommandPolicy.shouldSendInbox("@helper_bot hello", false, true))
        assertFalse(BotCommandPolicy.shouldSendInbox("@ab hello", false, true))
        assertFalse(BotCommandPolicy.shouldSendInbox("hello world", false, true))
        assertTrue(BotCommandPolicy.shouldSendInbox("/ping", false, true))
        assertFalse(BotCommandPolicy.shouldSendInbox("/ping", false, false))
    }
}
