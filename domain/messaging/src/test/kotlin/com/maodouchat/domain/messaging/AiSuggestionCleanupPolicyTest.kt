package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AiSuggestionCleanupPolicyTest {

    @Test
    fun `cleanSuggestions strips bullets and numbers and drops blank lines`() {
        val raw = "- hello\n* world\n1. foo\n\n  bar  "
        assertEquals(
            listOf("hello", "world", "foo", "bar"),
            AiSuggestionCleanupPolicy.cleanSuggestions(raw, 4)
        )
    }

    @Test
    fun `cleanSuggestions takes at most count lines`() {
        val raw = "a\nb\nc\nd"
        assertEquals(listOf("a", "b"), AiSuggestionCleanupPolicy.cleanSuggestions(raw, 2))
    }

    @Test
    fun `cleanSuggestions clamps count to 1 to 4`() {
        val raw = "a\nb\nc\nd\ne"
        assertEquals(listOf("a"), AiSuggestionCleanupPolicy.cleanSuggestions(raw, 0))
        assertEquals(listOf("a", "b", "c", "d"), AiSuggestionCleanupPolicy.cleanSuggestions(raw, 9))
    }

    @Test
    fun `cleanSuggestions returns empty list for blank input`() {
        assertEquals(emptyList<String>(), AiSuggestionCleanupPolicy.cleanSuggestions("\n  \n", 4))
    }
}
