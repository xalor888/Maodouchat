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

    @Test
    fun `max length boundary is inclusive`() {
        val exact = "a".repeat(QuickPhrasePolicy.MAX_PHRASE_LENGTH)
        assertTrue(QuickPhrasePolicy.isAddable(emptyList(), exact))
        assertEquals(listOf(exact), QuickPhrasePolicy.add(emptyList(), exact))
        assertFalse(QuickPhrasePolicy.isAddable(emptyList(), exact + "a"))
    }

    @Test
    fun `duplicate check trims but is case sensitive`() {
        assertEquals(listOf("好的"), QuickPhrasePolicy.add(listOf("好的"), "好的 "))
        assertEquals(listOf("OK", "ok"), QuickPhrasePolicy.add(listOf("OK"), "ok"))
    }

    @Test
    fun `remove does not trim`() {
        assertEquals(listOf("a", "b"), QuickPhrasePolicy.remove(listOf("a", "b"), " b "))
    }

    @Test
    fun `default phrases are all distinct`() {
        assertEquals(8, QuickPhrasePolicy.DEFAULT_PHRASES.toSet().size)
    }

    @Test
    fun `add appends at the end without touching the input`() {
        val base = listOf("a")
        val result = QuickPhrasePolicy.add(base, "b")
        assertEquals(listOf("a", "b"), result)
        assertEquals(listOf("a"), base)
    }

    @Test
    fun `length limit applies after trimming`() {
        val paddedExact = " " + "a".repeat(QuickPhrasePolicy.MAX_PHRASE_LENGTH) + " "
        assertTrue(QuickPhrasePolicy.isAddable(emptyList(), paddedExact))
        assertEquals(
            listOf("a".repeat(QuickPhrasePolicy.MAX_PHRASE_LENGTH)),
            QuickPhrasePolicy.add(emptyList(), paddedExact),
        )
        val paddedOver = " " + "a".repeat(QuickPhrasePolicy.MAX_PHRASE_LENGTH + 1) + " "
        assertFalse(QuickPhrasePolicy.isAddable(emptyList(), paddedOver))
    }

    @Test
    fun `remove drops every duplicate occurrence`() {
        assertEquals(listOf("b"), QuickPhrasePolicy.remove(listOf("a", "b", "a"), "a"))
    }

    @Test
    fun `capacity boundary admits the fortieth phrase only`() {
        val almostFull = List(QuickPhrasePolicy.MAX_PHRASES - 1) { "p$it" }
        val full = QuickPhrasePolicy.add(almostFull, "last")
        assertEquals(QuickPhrasePolicy.MAX_PHRASES, full.size)
        assertFalse(QuickPhrasePolicy.isAddable(full, "overflow"))
        assertEquals(full, QuickPhrasePolicy.add(full, "overflow"))
    }

    @Test
    fun `default phrases are exactly eight and each is addable`() {
        assertEquals(8, QuickPhrasePolicy.DEFAULT_PHRASES.size)
        QuickPhrasePolicy.DEFAULT_PHRASES.forEach { phrase ->
            assertTrue(QuickPhrasePolicy.isAddable(emptyList(), phrase))
        }
    }

    @Test
    fun `add trims ends but keeps inner spaces`() {
        assertEquals(listOf("hello world"), QuickPhrasePolicy.add(emptyList(), "  hello world  "))
    }


    @Test
    fun `isAddable and add reject tab and newline only phrases`() {
        assertFalse(QuickPhrasePolicy.isAddable(listOf("a"), "\t\n"))
        assertEquals(listOf("a"), QuickPhrasePolicy.add(listOf("a"), "\t\n"))
    }

    @Test
    fun `remove is case sensitive`() {
        assertEquals(listOf("OK", "Ok"), QuickPhrasePolicy.remove(listOf("OK", "ok", "Ok"), "ok"))
    }

    @Test
    fun `full list rejects a duplicate as well as an overflow`() {
        val full = List(QuickPhrasePolicy.MAX_PHRASES) { "p$it" }
        assertFalse(QuickPhrasePolicy.isAddable(full, "p0"))
        assertEquals(full, QuickPhrasePolicy.add(full, "p0"))
    }

    @Test
    fun `max constants are pinned`() {
        assertEquals(40, QuickPhrasePolicy.MAX_PHRASES)
        assertEquals(80, QuickPhrasePolicy.MAX_PHRASE_LENGTH)
    }

    @Test
    fun `add trims emoji phrase ends but keeps the emoji`() {
        assertEquals(listOf("好的 👍"), QuickPhrasePolicy.add(emptyList(), "  好的 👍  "))
    }

    @Test
    fun `remove preserves order and duplicates of other phrases`() {
        assertEquals(
            listOf("b", "b", "c"),
            QuickPhrasePolicy.remove(listOf("b", "a", "b", "c"), "a")
        )
    }

    @Test
    fun `default phrases carry no leading or trailing whitespace`() {
        QuickPhrasePolicy.DEFAULT_PHRASES.forEach { phrase ->
            assertEquals(phrase, phrase.trim())
        }
    }

    @Test
    fun `default phrases first and last are pinned`() {
        assertEquals("好的，收到 👍", QuickPhrasePolicy.DEFAULT_PHRASES.first())
        assertEquals("好的，马上来", QuickPhrasePolicy.DEFAULT_PHRASES.last())
    }

    @Test
    fun `add overlong phrase leaves the input untouched`() {
        val base = listOf("a")
        assertEquals(base, QuickPhrasePolicy.add(base, "x".repeat(81)))
        assertEquals(listOf("a"), base)
    }

    @Test
    fun `accepted add creates a new list but rejected add returns the input`() {
        val base = listOf("a")
        val added = QuickPhrasePolicy.add(base, "b")
        assertEquals(listOf("a", "b"), added)
        assertTrue(added !== base)
        assertTrue(QuickPhrasePolicy.add(base, "a") === base)
    }

    @Test
    fun `default phrases are pinned verbatim`() {
        assertEquals(
            listOf(
                "好的，收到 👍",
                "稍等，我看一下",
                "在忙，稍后回复你",
                "谢谢你！",
                "没问题",
                "哈哈哈 😄",
                "收到",
                "好的，马上来",
            ),
            QuickPhrasePolicy.DEFAULT_PHRASES,
        )
    }

    @Test
    fun `length limit counts multi-byte chars not bytes`() {
        assertTrue(QuickPhrasePolicy.isAddable(emptyList(), "忙".repeat(80)))
        assertEquals(listOf("忙".repeat(80)), QuickPhrasePolicy.add(emptyList(), "忙".repeat(80)))
        assertFalse(QuickPhrasePolicy.isAddable(emptyList(), "忙".repeat(81)))
    }

    @Test
    fun `full list rejects a blank phrase too`() {
        val full = List(QuickPhrasePolicy.MAX_PHRASES) { "p$it" }
        assertFalse(QuickPhrasePolicy.isAddable(full, "   "))
        assertEquals(full, QuickPhrasePolicy.add(full, "   "))
    }

    @Test
    fun `remove matches inner-space phrases exactly and tolerates empty input`() {
        assertEquals(listOf("c"), QuickPhrasePolicy.remove(listOf("a b", "c"), "a b"))
        assertEquals(emptyList<String>(), QuickPhrasePolicy.remove(emptyList(), "x"))
    }

    @Test
    fun `duplicate check compares the trimmed phrase exactly`() {
        assertEquals(
            listOf("hello world", "hello  world"),
            QuickPhrasePolicy.add(listOf("hello world"), "hello  world"),
        )
    }

}
