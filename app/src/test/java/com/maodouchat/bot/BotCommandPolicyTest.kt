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

    @Test
    fun parseSlashRejectsUppercaseAndOverlongCommands() {
        // 命令与目标用户名只认小写：大写直接整串不匹配，不是转小写后放行。
        assertEquals(null, BotCommandPolicy.parseSlash("/HELP"))
        assertEquals(null, BotCommandPolicy.parseSlash("/help@HELPER_BOT"))
        assertEquals(null, BotCommandPolicy.parseSlash("/1help"))
        assertEquals("a", BotCommandPolicy.parseSlash("/a")?.command)
        assertEquals("a".repeat(32), BotCommandPolicy.parseSlash("/a" + "a".repeat(31))?.command)
        assertEquals(null, BotCommandPolicy.parseSlash("/a" + "a".repeat(32)))
    }

    @Test
    fun ciphertextDetectionIsCaseSensitiveAndSubstringBased() {
        assertFalse(BotCommandPolicy.looksLikeCiphertext("sk:abc"))
        assertTrue(BotCommandPolicy.looksLikeCiphertext("SK:"))
        assertTrue(BotCommandPolicy.looksLikeCiphertext("  ENC:xyz  "))
        assertTrue(BotCommandPolicy.looksLikeCiphertext("prefix \"senderKey\" suffix"))
        assertFalse(BotCommandPolicy.looksLikeCiphertext("hello ciphertext world"))
        assertFalse(BotCommandPolicy.looksLikeCiphertext(""))
        assertFalse(BotCommandPolicy.looksLikeCiphertext("   "))
    }

    @Test
    fun directChatCiphertextAndBlankNeverGoToInbox() {
        assertFalse(BotCommandPolicy.shouldSendInbox("SK:x", true, false))
        assertFalse(BotCommandPolicy.shouldSendInbox("", true, false))
        assertFalse(BotCommandPolicy.shouldSendInbox("   ", true, false))
        assertTrue(BotCommandPolicy.shouldSendInbox("123", true, false))
        assertTrue(BotCommandPolicy.shouldSendInbox("@abc hi", false, true))
        assertFalse(BotCommandPolicy.shouldSendInbox("@helper-bot hi", false, true))
        assertTrue(BotCommandPolicy.shouldSendInbox(" @helper_bot hi ", false, true))
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
    fun parseSlashRejectsTooShortTargetButKeepsArgs() {
        assertEquals(null, BotCommandPolicy.parseSlash("/start@ab"))
        val targeted = BotCommandPolicy.parseSlash("/start@helper_bot arg1")
        assertEquals("start", targeted?.command)
        assertEquals("helper_bot", targeted?.targetUsername)
        assertEquals("arg1", targeted?.arguments)
    }

    @Test
    fun parseSlashToleratesTrailingSpaceAndKeepsInnerSpacing() {
        val bare = BotCommandPolicy.parseSlash("/help ")
        assertEquals("help", bare?.command)
        assertEquals("", bare?.arguments)
        val spaced = BotCommandPolicy.parseSlash("/start  double  space")
        assertEquals("double  space", spaced?.arguments)
    }

    @Test
    fun parseSlashAcceptsDigitsAndUnderscoresInCommand() {
        assertEquals("h3lp_me", BotCommandPolicy.parseSlash("/h3lp_me")?.command)
        assertEquals("a_b", BotCommandPolicy.parseSlash("/a_b")?.command)
    }

    @Test
    fun bareAtMentionGoesToInboxButMidTextMentionDoesNot() {
        assertTrue(BotCommandPolicy.shouldSendInbox("@helper_bot", false, true))
        assertFalse(BotCommandPolicy.shouldSendInbox("@ab", false, true))
        assertFalse(BotCommandPolicy.shouldSendInbox("hello @helper_bot", false, true))
    }

    @Test
    fun midStringCiphertextMarkerIsNotTreatedAsCiphertext() {
        assertFalse(BotCommandPolicy.looksLikeCiphertext("prefix SK:abc"))
        assertTrue(BotCommandPolicy.shouldSendInbox("note SK:abc", true, false))
        assertFalse(BotCommandPolicy.isBotUserId("bot"))
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
