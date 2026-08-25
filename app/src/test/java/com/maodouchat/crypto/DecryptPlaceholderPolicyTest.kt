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
}
