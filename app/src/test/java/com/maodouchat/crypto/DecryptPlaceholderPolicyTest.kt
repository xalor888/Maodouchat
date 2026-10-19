package com.maodouchat.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecryptPlaceholderPolicyTest {

    @Test
    fun `exact legacy placeholders are recognized`() {
        listOf(
            "无法解密",
            "decrypt failed",
            "session missing",
            "群密钥缺失",
            "[无法解密的消息]",
            "[Encrypted message]",
            "[Missing session key. Waiting for resend]",
        ).forEach { placeholder ->
            assertTrue(placeholder, DecryptPlaceholderPolicy.isPlaceholder(placeholder))
        }
    }

    @Test
    fun `ordinary sentences containing decrypt terms are not placeholders`() {
        listOf(
            "这个怎么解密？",
            "这个消息该怎么解密？",
            "decrypt this message",
            "How do I decrypt this?",
            "session missing 是什么意思？",
        ).forEach { plaintext ->
            assertFalse(plaintext, DecryptPlaceholderPolicy.isPlaceholder(plaintext))
        }
    }

    @Test
    fun `whitespace and case variants still match`() {
        listOf(
            "  DECRYPT FAILED  ",
            "[Encrypted message]",
            "[无法解密的消息]  ",
        ).forEach { variant ->
            assertTrue(variant, DecryptPlaceholderPolicy.isPlaceholder(variant))
        }
    }

    @Test
    fun `localized placeholders are honored`() {
        assertTrue(
            DecryptPlaceholderPolicy.isPlaceholder("[无法恢复的消息]", "[无法恢复的消息]")
        )
        assertFalse(
            DecryptPlaceholderPolicy.isPlaceholder("普通文本", "[无法恢复的消息]")
        )
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("", "[无法恢复的消息]"))
    }

    @Test
    fun `blank content is never a placeholder`() {
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder(""))
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("   "))
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("\n\t"))
    }

    @Test
    fun `near-miss strings are not placeholders`() {
        listOf(
            "[无法解密",
            "无法解密的消息",
            "decryptfailed",
            "[decrypt failed]",
        ).forEach { nearMiss ->
            assertFalse(nearMiss, DecryptPlaceholderPolicy.isPlaceholder(nearMiss))
        }
    }

    @Test
    fun `localized matching ignores case and surrounding spaces`() {
        assertTrue(
            DecryptPlaceholderPolicy.isPlaceholder("  [MESSAGE GONE]  ", "[message gone]")
        )
    }
}
