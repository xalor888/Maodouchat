package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuickReplyPolicyTest {

    @Test
    fun `valid quick reply`() {
        assertTrue(QuickReplyPolicy.validate("c1", "hi"))
    }

    @Test
    fun `blank text or conversation is invalid`() {
        assertFalse(QuickReplyPolicy.validate("", "hi"))
        assertFalse(QuickReplyPolicy.validate("c1", "  "))
    }

    @Test
    fun `overlong text is invalid`() {
        assertFalse(QuickReplyPolicy.validate("c1", "x".repeat(4001)))
        assertTrue(QuickReplyPolicy.validate("c1", "x".repeat(4000)))
    }

    @Test
    fun `whitespace-only conversation id is invalid`() {
        assertFalse(QuickReplyPolicy.validate("   ", "hi"))
        assertFalse(QuickReplyPolicy.validate("\t", "hi"))
    }

    @Test
    fun `newline-only text is invalid`() {
        assertFalse(QuickReplyPolicy.validate("c1", "\n"))
        assertFalse(QuickReplyPolicy.validate("c1", " \n\t "))
    }

    @Test
    fun `empty text is invalid`() {
        assertFalse(QuickReplyPolicy.validate("c1", ""))
    }

    @Test
    fun `max length counts characters not bytes`() {
        assertTrue(QuickReplyPolicy.validate("c1", "中".repeat(4000)))
        assertFalse(QuickReplyPolicy.validate("c1", "中".repeat(4001)))
    }

    @Test
    fun `emoji length counts utf16 code units`() {
        // String.length 按 UTF-16 code unit 计，一个 emoji 占 2。
        assertTrue(QuickReplyPolicy.validate("c1", "😀".repeat(2000)))
        assertFalse(QuickReplyPolicy.validate("c1", "😀".repeat(2001)))
    }

    @Test
    fun `max text length constant is pinned at 4000`() {
        assertEquals(4000, QuickReplyPolicy.MAX_TEXT_LENGTH)
        assertTrue(QuickReplyPolicy.validate("c1", "x".repeat(QuickReplyPolicy.MAX_TEXT_LENGTH)))
        assertFalse(QuickReplyPolicy.validate("c1", "x".repeat(QuickReplyPolicy.MAX_TEXT_LENGTH + 1)))
    }

    @Test
    fun `validate is deterministic across repeated calls`() {
        repeat(3) {
            assertTrue(QuickReplyPolicy.validate("c1", "hi"))
            assertFalse(QuickReplyPolicy.validate("c1", ""))
            assertFalse(QuickReplyPolicy.validate("   ", "x".repeat(4001)))
        }
    }

    @Test
    fun `conversation id with surrounding whitespace is accepted`() {
        assertTrue(QuickReplyPolicy.validate(" c1 ", "hi"))
        assertTrue(QuickReplyPolicy.validate("\nc1\t", "hi"))
    }

    @Test
    fun `text with whitespace around real content is valid`() {
        assertTrue(QuickReplyPolicy.validate("c1", "  hi\n"))
        assertTrue(QuickReplyPolicy.validate("c1", "\t ok \t"))
    }

    @Test
    fun `length is measured on the raw string, blank check still wins at the limit`() {
        assertTrue(QuickReplyPolicy.validate("c1", "x".repeat(3999) + "\n"))
        assertFalse(QuickReplyPolicy.validate("c1", " ".repeat(4000)))
    }
}
