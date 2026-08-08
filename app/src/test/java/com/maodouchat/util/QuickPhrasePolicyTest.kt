package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPhrasePolicyTest {

    @Test
    fun `default phrases are non-empty and within limits`() {
        assertTrue(QuickPhrasePolicy.DEFAULT_PHRASES.isNotEmpty())
        QuickPhrasePolicy.DEFAULT_PHRASES.forEach { phrase ->
            assertTrue(phrase.isNotBlank())
            assertTrue(phrase.length <= QuickPhrasePolicy.MAX_PHRASE_LENGTH)
        }
        assertTrue(QuickPhrasePolicy.DEFAULT_PHRASES.size <= QuickPhrasePolicy.MAX_PHRASES)
    }

    @Test
    fun `add trims and rejects blanks`() {
        val base = emptyList<String>()
        assertEquals(listOf("好的"), QuickPhrasePolicy.add(base, "  好的  "))
        assertEquals(base, QuickPhrasePolicy.add(base, "   "))
    }

    @Test
    fun `add rejects duplicates`() {
        val base = listOf("好的")
        assertEquals(base, QuickPhrasePolicy.add(base, "好的"))
        assertFalse(QuickPhrasePolicy.isAddable(base, "好的"))
    }

    @Test
    fun `add enforces max length and count`() {
        val longPhrase = "a".repeat(QuickPhrasePolicy.MAX_PHRASE_LENGTH + 1)
        assertFalse(QuickPhrasePolicy.isAddable(emptyList(), longPhrase))

        val full = List(QuickPhrasePolicy.MAX_PHRASES) { "p$it" }
        assertFalse(QuickPhrasePolicy.isAddable(full, "overflow"))
        assertEquals(full, QuickPhrasePolicy.add(full, "overflow"))
    }

    @Test
    fun `remove filters exact match only`() {
        assertEquals(listOf("a", "c"), QuickPhrasePolicy.remove(listOf("a", "b", "c"), "b"))
        assertEquals(listOf("a", "b"), QuickPhrasePolicy.remove(listOf("a", "b"), "z"))
    }
}
