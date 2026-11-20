package com.maodouchat.domain.groups

import com.maodouchat.core.model.BotCommandPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotInboxPolicyTest {

    @Test
    fun groupCiphertextNeverGoesToInbox() {
        assertFalse(
            BotInboxPolicy.shouldSendInbox(
                text = """{"ciphertext":"abc"}""",
                isDirectWithBot = false,
                hasGroupBots = true,
            )
        )
        assertFalse(
            BotInboxPolicy.shouldSendInbox(
                text = "hello",
                isDirectWithBot = false,
                hasGroupBots = true,
            )
        )
        assertTrue(
            BotInboxPolicy.shouldSendInbox(
                text = "/help",
                isDirectWithBot = false,
                hasGroupBots = true,
            )
        )
        assertTrue(
            BotInboxPolicy.shouldSendInbox(
                text = "hello",
                isDirectWithBot = true,
                hasGroupBots = false,
            )
        )
    }

    @Test
    fun parseSlashExtractsCommandTargetAndArguments() {
        val bare = BotInboxPolicy.parseSlash("/help")
        assertEquals("help", bare?.command)
        assertEquals(null, bare?.targetUsername)
        assertEquals("", bare?.arguments)

        val targeted = BotInboxPolicy.parseSlash("/start@helper_bot")
        assertEquals("start", targeted?.command)
        assertEquals("helper_bot", targeted?.targetUsername)
        assertEquals("", targeted?.arguments)

        val withArgs = BotInboxPolicy.parseSlash("/start arg1 arg2")
        assertEquals("start", withArgs?.command)
        assertEquals("arg1 arg2", withArgs?.arguments)

        assertEquals(null, BotInboxPolicy.parseSlash("hello"))
        assertEquals(null, BotInboxPolicy.parseSlash("/"))
    }

    @Test
    fun ciphertextPrefixesNeverGoToInbox() {
        assertFalse(BotInboxPolicy.shouldSendInbox("SK:abc", true, false))
        assertFalse(BotInboxPolicy.shouldSendInbox("ENC:xyz", false, true))
        assertFalse(BotInboxPolicy.shouldSendInbox("""{"senderKey":"k"}""", false, true))
        assertTrue(BotInboxPolicy.shouldSendInbox("  /help  ", false, true))
    }

    @Test
    fun atMentionOnlyValidBotShapesGoToInbox() {
        assertTrue(BotInboxPolicy.shouldSendInbox("@helper_bot hello", false, true))
        assertFalse(BotInboxPolicy.shouldSendInbox("@ab hello", false, true))
        assertFalse(BotInboxPolicy.shouldSendInbox("hello world", false, true))
        assertTrue(BotInboxPolicy.shouldSendInbox("/ping", false, true))
        assertFalse(BotInboxPolicy.shouldSendInbox("/ping", false, false))
    }

    @Test
    fun parseSlashRejectsUppercaseAndOverlongCommands() {
        // 命令与目标用户名只认小写：大写直接整串不匹配，不是转小写后放行。
        assertEquals(null, BotInboxPolicy.parseSlash("/HELP"))
        assertEquals(null, BotInboxPolicy.parseSlash("/help@HELPER_BOT"))
        assertEquals(null, BotInboxPolicy.parseSlash("/1help"))
        assertEquals("a", BotInboxPolicy.parseSlash("/a")?.command)
        assertEquals("a".repeat(32), BotInboxPolicy.parseSlash("/a" + "a".repeat(31))?.command)
        assertEquals(null, BotInboxPolicy.parseSlash("/a" + "a".repeat(32)))
    }

    @Test
    fun ciphertextDetectionIsCaseSensitiveAndSubstringBased() {
        assertFalse(BotInboxPolicy.looksLikeCiphertext("sk:abc"))
        assertTrue(BotInboxPolicy.looksLikeCiphertext("SK:"))
        assertTrue(BotInboxPolicy.looksLikeCiphertext("  ENC:xyz  "))
        assertTrue(BotInboxPolicy.looksLikeCiphertext("prefix \"senderKey\" suffix"))
        assertFalse(BotInboxPolicy.looksLikeCiphertext("hello ciphertext world"))
        assertFalse(BotInboxPolicy.looksLikeCiphertext(""))
        assertFalse(BotInboxPolicy.looksLikeCiphertext("   "))
    }

    @Test
    fun directChatCiphertextAndBlankNeverGoToInbox() {
        assertFalse(BotInboxPolicy.shouldSendInbox("SK:x", true, false))
        assertFalse(BotInboxPolicy.shouldSendInbox("", true, false))
        assertFalse(BotInboxPolicy.shouldSendInbox("   ", true, false))
        assertTrue(BotInboxPolicy.shouldSendInbox("123", true, false))
        assertTrue(BotInboxPolicy.shouldSendInbox("@abc hi", false, true))
        assertFalse(BotInboxPolicy.shouldSendInbox("@helper-bot hi", false, true))
        assertTrue(BotInboxPolicy.shouldSendInbox(" @helper_bot hi ", false, true))
    }

    @Test
    fun parseSlashRejectsTooShortTargetButKeepsArgs() {
        assertEquals(null, BotInboxPolicy.parseSlash("/start@ab"))
        val targeted = BotInboxPolicy.parseSlash("/start@helper_bot arg1")
        assertEquals("start", targeted?.command)
        assertEquals("helper_bot", targeted?.targetUsername)
        assertEquals("arg1", targeted?.arguments)
    }

    @Test
    fun parseSlashToleratesTrailingSpaceAndKeepsInnerSpacing() {
        val bare = BotInboxPolicy.parseSlash("/help ")
        assertEquals("help", bare?.command)
        assertEquals("", bare?.arguments)
        val spaced = BotInboxPolicy.parseSlash("/start  double  space")
        assertEquals("double  space", spaced?.arguments)
    }

    @Test
    fun parseSlashAcceptsDigitsAndUnderscoresInCommand() {
        assertEquals("h3lp_me", BotInboxPolicy.parseSlash("/h3lp_me")?.command)
        assertEquals("a_b", BotInboxPolicy.parseSlash("/a_b")?.command)
    }

    @Test
    fun bareAtMentionGoesToInboxButMidTextMentionDoesNot() {
        assertTrue(BotInboxPolicy.shouldSendInbox("@helper_bot", false, true))
        assertFalse(BotInboxPolicy.shouldSendInbox("@ab", false, true))
        assertFalse(BotInboxPolicy.shouldSendInbox("hello @helper_bot", false, true))
    }

    @Test
    fun midStringCiphertextMarkerIsNotTreatedAsCiphertext() {
        assertFalse(BotInboxPolicy.looksLikeCiphertext("prefix SK:abc"))
        assertTrue(BotInboxPolicy.shouldSendInbox("note SK:abc", true, false))
        assertFalse(BotCommandPolicy.isBotUserId("bot"))
    }

    @Test
    fun `slash with target and args parses all three parts`() {
        val parsed = BotInboxPolicy.parseSlash("/start@helper_bot arg1 arg2")
        assertEquals("start", parsed?.command)
        assertEquals("helper_bot", parsed?.targetUsername)
        assertEquals("arg1 arg2", parsed?.arguments)
    }

    @Test
    fun `at-bot mention without group bots never goes to inbox`() {
        assertFalse(BotInboxPolicy.shouldSendInbox("@helper_bot hi", false, false))
        assertTrue(BotInboxPolicy.shouldSendInbox("@helper_bot hi", false, true))
    }
}
