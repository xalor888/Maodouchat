package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotCreateInputPolicyTest {

    @Test
    fun `name is clipped to 120 chars`() {
        assertEquals(120, BotCreateInputPolicy.clipName("a".repeat(200)).length)
        assertEquals("abc", BotCreateInputPolicy.clipName("abc"))
    }

    @Test
    fun `username keeps only letters digits underscore and lowercases`() {
        assertEquals("abcd_e1", BotCreateInputPolicy.sanitizeUsername("Ab C@d_e1"))
        assertEquals("a".repeat(32), BotCreateInputPolicy.sanitizeUsername("A".repeat(40)))
    }

    @Test
    fun `webhook url is clipped to 500 chars`() {
        assertEquals(500, BotCreateInputPolicy.clipWebhookUrl("h".repeat(600)).length)
        assertEquals("https://x", BotCreateInputPolicy.clipWebhookUrl("https://x"))
    }

    @Test
    fun `webhook url validation accepts empty and http(s)`() {
        assertTrue(BotCreateInputPolicy.isValidWebhookUrl(""))
        assertTrue(BotCreateInputPolicy.isValidWebhookUrl("https://example.com/hook"))
        assertTrue(BotCreateInputPolicy.isValidWebhookUrl("HTTP://example.com"))
        assertTrue(BotCreateInputPolicy.isValidWebhookUrl("  https://example.com  "))
        assertFalse(BotCreateInputPolicy.isValidWebhookUrl("ftp://example.com"))
        assertFalse(BotCreateInputPolicy.isValidWebhookUrl("example.com"))
    }
}
