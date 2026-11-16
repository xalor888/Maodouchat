package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmojiSearchPolicyTest {

    private val aliases = mapOf(
        "😀" to listOf("grin", "happy"),
        "🎉" to listOf("party", "celebrate"),
    )
    private val emojis = listOf("😀", "🎉", "🔥")

    @Test
    fun `matches emoji itself`() {
        assertTrue(EmojiSearchPolicy.matches("😀", aliases, "😀"))
    }

    @Test
    fun `matches alias case-insensitively`() {
        assertTrue(EmojiSearchPolicy.matches("🎉", aliases, "PARTY"))
    }

    @Test
    fun `no match returns false`() {
        assertFalse(EmojiSearchPolicy.matches("🔥", aliases, "party"))
    }

    @Test
    fun `filter blank returns all`() {
        assertEquals(emojis, EmojiSearchPolicy.filter(emojis, aliases, "  "))
    }

    @Test
    fun `filter trims query`() {
        assertEquals(listOf("😀"), EmojiSearchPolicy.filter(emojis, aliases, "  grin "))
    }

    @Test
    fun `filter no match returns empty`() {
        assertEquals(emptyList<String>(), EmojiSearchPolicy.filter(emojis, aliases, "zzz"))
    }
}
