package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class EmojiRecentPolicyTest {

    @Test
    fun pushRecent_prependsNewEmoji() {
        assertEquals(listOf("😀", "a", "b"), EmojiRecentPolicy.pushRecent(listOf("a", "b"), "😀"))
    }

    @Test
    fun pushRecent_blankLeavesListUnchanged() {
        assertEquals(listOf("a"), EmojiRecentPolicy.pushRecent(listOf("a"), "   "))
    }

    @Test
    fun pushRecent_dedupsAndMovesToFront() {
        assertEquals(listOf("b", "a"), EmojiRecentPolicy.pushRecent(listOf("a", "b"), "b"))
    }

    @Test
    fun pushRecent_truncatesToMaxRecent() {
        val existing = (1..36).map { "e$it" }
        val out = EmojiRecentPolicy.pushRecent(existing, "new")
        assertEquals(36, out.size)
        assertEquals("new", out.first())
        assertEquals("e35", out.last())
    }

    @Test
    fun pushRecent_customMaxIsHonored() {
        assertEquals(listOf("d", "a"), EmojiRecentPolicy.pushRecent(listOf("a", "b", "c"), "d", max = 2))
    }
}
