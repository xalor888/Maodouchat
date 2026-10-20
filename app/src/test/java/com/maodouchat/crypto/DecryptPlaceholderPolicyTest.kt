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

    @Test
    fun `trailing punctuation breaks the match`() {
        listOf(
            "[无法解密的消息]。",
            "[无法解密的消息]!",
            "[unable to decrypt message].",
        ).forEach { nearMiss ->
            assertFalse(nearMiss, DecryptPlaceholderPolicy.isPlaceholder(nearMiss))
        }
    }

    @Test
    fun `multiline whitespace around placeholder still matches`() {
        assertTrue(DecryptPlaceholderPolicy.isPlaceholder("\n\t [unable to decrypt voice message] \r\n"))
        assertTrue(DecryptPlaceholderPolicy.isPlaceholder(" \n[缺少会话密钥，请等待对方重新发送]\t "))
    }

    @Test
    fun `bracketed single word is not a placeholder`() {
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("[无法解密]"))
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("[解密失败]"))
    }

    @Test
    fun `localized vararg ignores order and surrounding whitespace`() {
        assertTrue(DecryptPlaceholderPolicy.isPlaceholder("  [无法恢复的消息]  ", "[无法恢复的消息]"))
        assertTrue(DecryptPlaceholderPolicy.isPlaceholder("[gone]", "[other]", "[gone]"))
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("  ", "[x]", "[y]"))
    }

    @Test
    fun `legacy match wins regardless of varargs`() {
        assertTrue(DecryptPlaceholderPolicy.isPlaceholder("decrypt failed", "[totally different]"))
        assertTrue(DecryptPlaceholderPolicy.isPlaceholder("[encrypted message]", "decrypt failed"))
    }

    @Test
    fun `remaining legacy entries are recognized`() {
        listOf(
            "[无法解密的群聊消息]",
            "[加密消息]",
            "[无法解密的 gif]",
            "[无法解密的贴纸]",
            "[无法解密的位置]",
            "[无法解密的语音]",
            "[缺少群聊 sender key，请等待成员重新发送]",
            "[unable to decrypt message]",
            "[unable to decrypt group message]",
            "[member safety number changed. verify identity]",
            "[newer group key epoch. refresh group info]",
            "[unable to decrypt gif]",
            "[unable to decrypt sticker]",
            "[unable to decrypt location]",
            "[unable to decrypt video]",
            "[unable to decrypt file]",
            "[unable to decrypt image]",
        ).forEach { placeholder ->
            assertTrue(placeholder, DecryptPlaceholderPolicy.isPlaceholder(placeholder))
        }
    }

    @Test
    fun `legacy english matching ignores case`() {
        listOf(
            "Identity Changed",
            "SESSION MISSING",
            "Decrypt Failed",
            "[UNABLE TO DECRYPT MESSAGE]",
            "[Missing Group Sender Key. Waiting For Redistribution]",
        ).forEach { variant ->
            assertTrue(variant, DecryptPlaceholderPolicy.isPlaceholder(variant))
        }
    }

    @Test
    fun `internal whitespace breaks legacy match`() {
        listOf(
            "[无法解密 的消息]",
            "无法 解密",
            "decrypt  failed",
            "[unable  to decrypt message]",
        ).forEach { nearMiss ->
            assertFalse(nearMiss, DecryptPlaceholderPolicy.isPlaceholder(nearMiss))
        }
    }

    @Test
    fun `newline inside placeholder breaks match`() {
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("[无法解密\n的消息]"))
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("decrypt\nfailed"))
        // 换行只在外层时仍可匹配
        assertTrue(DecryptPlaceholderPolicy.isPlaceholder("\ndecrypt failed\n"))
    }

    @Test
    fun `full-width brackets are not recognized`() {
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("［无法解密的消息］"))
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("【无法解密的消息】"))
    }

    @Test
    fun `non-breaking space around placeholder still matches`() {
        // Kotlin 的 trim() 把不间断空格也当空白裁掉，归一化后仍命中。
        assertTrue(DecryptPlaceholderPolicy.isPlaceholder("\u00a0无法解密\u00a0"))
        assertTrue(DecryptPlaceholderPolicy.isPlaceholder("\u00a0[unable to decrypt message]\u00a0"))
    }

    @Test
    fun `all-blank varargs never match`() {
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("普通文本", "", "   ", "\t"))
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("gone", "", "  "))
    }

    @Test
    fun `placeholder embedded in prose is not a match`() {
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("xx 无法解密 yy"))
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("prefix [unable to decrypt message] suffix"))
    }

    @Test
    fun `repeated calls return stable results`() {
        repeat(3) {
            assertTrue(DecryptPlaceholderPolicy.isPlaceholder("无法解密"))
            assertFalse(DecryptPlaceholderPolicy.isPlaceholder("无法解密哦"))
        }
    }

    @Test
    fun `zero-width space inside placeholder breaks match`() {
        // 零宽空格既不被 trim 裁掉也不被 lowercase 归一，精确匹配失效。
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("无法\u200b解密"))
        assertFalse(DecryptPlaceholderPolicy.isPlaceholder("[无法解密的消息]\u200b"))
    }
}
