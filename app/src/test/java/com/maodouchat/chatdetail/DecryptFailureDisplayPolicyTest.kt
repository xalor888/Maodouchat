package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.mergeMessageVersions
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * XAL-20：解密失败不得覆盖已可读明文，也不得在重开会话时把占位文案互相覆盖成抖动 UI。
 * 对应 ChatDetailViewModel 把 DecryptResult 映射为文案后，走 mergeMessageVersions 落库/展示。
 */
class DecryptFailureDisplayPolicyTest {

    @Test
    fun `plaintext wins over decrypt failure placeholder on equal revision`() {
        val readable = message("m1", "hello")
        val failed = message("m1", "无法解密")

        assertEquals("hello", mergeMessageVersions(listOf(readable), listOf(failed)).single().content)
        assertEquals("hello", mergeMessageVersions(listOf(failed), listOf(readable)).single().content)
    }

    @Test
    fun `plaintext wins over english decrypt failed and session missing placeholders`() {
        val readable = message("m1", "ok")
        val decryptFailed = message("m1", "decrypt failed")
        val sessionMissing = message("m1", "session missing")

        assertEquals("ok", mergeMessageVersions(listOf(readable), listOf(decryptFailed)).single().content)
        assertEquals("ok", mergeMessageVersions(listOf(sessionMissing), listOf(readable)).single().content)
    }

    @Test
    fun `plaintext wins over group key missing placeholder`() {
        val readable = message("m1", "群消息")
        val keyMissing = message("m1", "群密钥缺失")

        assertEquals("群消息", mergeMessageVersions(listOf(readable), listOf(keyMissing)).single().content)
    }

    @Test
    fun `plaintext wins over wire ciphertext envelope`() {
        val readable = message("m1", "hello")
        val wire = message(
            "m1",
            """{"version":1,"algorithm":"signal-sender-key-v1","ciphertext":"abc"}"""
        )

        assertEquals("hello", mergeMessageVersions(listOf(readable), listOf(wire)).single().content)
        assertEquals("hello", mergeMessageVersions(listOf(wire), listOf(readable)).single().content)
    }

    @Test
    fun `both decrypt failure placeholders keep the current stable copy`() {
        val current = message("m1", "无法解密")
        val incoming = message("m1", "decrypt failed")

        assertEquals("无法解密", mergeMessageVersions(listOf(current), listOf(incoming)).single().content)
    }

    @Test
    fun `newer edit still cannot replace plaintext with decrypt placeholder`() {
        val readable = message("m1", "hello").copy(editedAt = 100L)
        val failed = message("m1", "无法解密").copy(editedAt = 200L)

        val merged = mergeMessageVersions(listOf(readable), listOf(failed)).single()
        assertEquals("hello", merged.content)
        assertEquals(200L, merged.editedAt)
    }

    private fun message(id: String, content: String) = Message(
        id = id,
        chatId = "chat-1",
        senderId = "user-1",
        content = content,
        type = MessageType.TEXT,
        timestamp = 100L,
        status = MessageStatus.SENT
    )
}
