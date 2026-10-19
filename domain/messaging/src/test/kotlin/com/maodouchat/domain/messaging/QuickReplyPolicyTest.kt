package com.maodouchat.domain.messaging

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
}
