package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class AiOfflineSuggestionPolicyTest {

    @Test
    fun `reply truncates at 500`() {
        val out = AiOfflineSuggestionPolicy.prepareReplies(listOf("x".repeat(501)))
        assertEquals(listOf("x".repeat(500)), out)
    }

    @Test
    fun `blank replies are filtered`() {
        val out = AiOfflineSuggestionPolicy.prepareReplies(listOf("  ", "ok", ""))
        assertEquals(listOf("ok"), out)
    }

    @Test
    fun `at most 4 replies survive`() {
        val out = AiOfflineSuggestionPolicy.prepareReplies(listOf("a", "b", "c", "d", "e"))
        assertEquals(listOf("a", "b", "c", "d"), out)
    }

    @Test
    fun `seed truncates at 120`() {
        assertEquals("x".repeat(120), AiOfflineSuggestionPolicy.buildSeed("x".repeat(121)))
    }

    @Test
    fun `recent context takes last 3 joined and truncates at 240`() {
        val texts = listOf("one", "two", "three", "four")
        assertEquals("two three four", AiOfflineSuggestionPolicy.buildRecent(texts))
        val long = AiOfflineSuggestionPolicy.buildRecent(listOf("x".repeat(300)))
        assertEquals(240, long.length)
    }
}
