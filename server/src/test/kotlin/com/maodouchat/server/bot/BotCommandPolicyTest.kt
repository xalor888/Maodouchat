package com.maodouchat.server.bot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotCommandPolicyTest {

    @Test
    fun ciphertextIsNeverACommand() {
        assertNull(BotCommandPolicy.sanitizeInboxText("""{"ciphertext":"abc"}"""))
        assertNull(BotCommandPolicy.sanitizeInboxText("SK:deadbeef"))
        assertFalse(BotCommandPolicy.shouldAcceptInbox("ENC:aaaa", isDirectWithBot = true))
        assertFalse(BotCommandPolicy.shouldAcceptInbox("SK:xx", isDirectWithBot = false))
    }

    @Test
    fun slashAndMentionParse() {
        val slash = BotCommandPolicy.parseSlash("/help@weather_bot tomorrow")
        assertEquals("help", slash?.command)
        assertEquals("weather_bot", slash?.targetUsername)
        assertEquals("tomorrow", slash?.arguments)
        assertEquals("ping", BotCommandPolicy.parseSlash("/ping")?.command)
        val mention = BotCommandPolicy.parseMention("@helper_bot 查天气")
        assertEquals("helper_bot", mention?.username)
        assertEquals("查天气", mention?.text)
        assertTrue(BotCommandPolicy.shouldAcceptInbox("/start", isDirectWithBot = false))
        assertTrue(BotCommandPolicy.shouldAcceptInbox("@helper_bot hi", isDirectWithBot = false))
        assertFalse(BotCommandPolicy.shouldAcceptInbox("hello everyone", isDirectWithBot = false))
        assertTrue(BotCommandPolicy.shouldAcceptInbox("hello everyone", isDirectWithBot = true))
    }

    @Test
    fun composerMenuOnlyForSlashDrafts() {
        assertTrue(BotCommandPolicy.shouldOfferComposerMenu("/he"))
        assertTrue(BotCommandPolicy.shouldOfferComposerMenu("/"))
        assertFalse(BotCommandPolicy.shouldOfferComposerMenu("hello"))
        assertFalse(BotCommandPolicy.shouldOfferComposerMenu("/help\nmore"))
        assertEquals("he", BotCommandPolicy.composerMenuQuery("/he"))
    }
}
