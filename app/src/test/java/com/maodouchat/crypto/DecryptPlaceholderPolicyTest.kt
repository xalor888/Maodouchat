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

    @Test
    fun `media type placeholders are recognized`() {
        listOf(
            "[无法解密的图片]",
            "[无法解密的视频]",
            "[无法解密的文件]",
            "[unable to decrypt voice message]",
        ).forEach { placeholder ->
            assertTrue(placeholder, DecryptPlaceholderPolicy.isPlaceholder(placeholder))
        }
    }

    @Test
    fun `identity change placeholders are recognized`() {
        listOf(
            "identity changed",
            "[safety number changed. verify identity]",
            "[对方安全码已变化，请验证身份]",
            "[群成员安全码已变化，请验证身份]",
        ).forEach { placeholder ->
            assertTrue(placeholder, DecryptPlaceholderPolicy.isPlaceholder(placeholder))
        }
    }

    @Test
    fun `key missing variants are recognized`() {
        listOf(
            "解密失败",
            "密钥缺失",
            "[缺少会话密钥，请等待对方重新发送]",
            "[missing group sender key. waiting for redistribution]",
            "[群聊密钥 epoch 较新，请刷新群信息]",
        ).forEach { placeholder ->
            assertTrue(placeholder, DecryptPlaceholderPolicy.isPlaceholder(placeholder))
        }
    }

    @Test
    fun `localized placeholder requires a full normalized match`() {
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("message gone", "[message gone]"))
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("[message gone]", "message gone"))
        assertTrue(DecryptPlaceholderPolicy.isPlaceholder("gone", "[gone]", "gone"))
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("  ", "[x]"))
    }

    @Test
    fun `english placeholders match regardless of case`() {
        listOf(
            "[UNABLE TO DECRYPT IMAGE]",
            "  [ENCRYPTED MESSAGE]  ",
            "[Missing Session Key. Waiting For Resend]",
        ).forEach { variant ->
            assertTrue(variant, DecryptPlaceholderPolicy.isPlaceholder(variant))
        }
    }
}
