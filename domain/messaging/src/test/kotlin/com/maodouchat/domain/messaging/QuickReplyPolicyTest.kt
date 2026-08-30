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
}
