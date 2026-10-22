package com.maodouchat.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.mergeMessageVersions
import org.junit.Assert.assertEquals
import org.junit.Test

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

    @Test
    fun `decrypt related user plaintext is kept when merged with wire`() {
        val wire = message(
            "m1",
            """{"senderDeviceId":76,"payloadType":"TEXT","entries":[{"ciphertext":"NAgB"}]}""",
        )
        listOf(
            "这个怎么解密？",
            "decrypt this message",
            "How do I decrypt this?",
            "session missing 是什么意思？",
        ).forEach { content ->
            val readable = message("m1", content)
            assertEquals(content, mergeMessageVersions(listOf(readable), listOf(wire)).single().content)
            assertEquals(content, mergeMessageVersions(listOf(wire), listOf(readable)).single().content)
        }
    }

    @Test
    fun `revoked is terminal and never replaced by plaintext`() {
        val revoked = message("m1", "hello").copy(type = MessageType.REVOKED)
        val readable = message("m1", "hello")

        assertEquals(revoked, mergeMessageVersions(listOf(revoked), listOf(readable)).single())
        assertEquals(revoked, mergeMessageVersions(listOf(readable), listOf(revoked)).single())
    }

    @Test
    fun `equal revision never regresses local delivery status`() {
        val delivered = message("m1", "hello").copy(status = MessageStatus.DELIVERED)
        val read = message("m1", "hello").copy(status = MessageStatus.READ)

        assertEquals(MessageStatus.READ, mergeMessageVersions(listOf(delivered), listOf(read)).single().status)
        assertEquals(MessageStatus.READ, mergeMessageVersions(listOf(read), listOf(delivered)).single().status)
    }

    @Test
    fun `failed side channel never lingers when server state arrives`() {
        val failed = message("m1", "hello").copy(status = MessageStatus.FAILED)
        val sent = message("m1", "hello").copy(status = MessageStatus.SENT)

        assertEquals(MessageStatus.SENT, mergeMessageVersions(listOf(failed), listOf(sent)).single().status)
        assertEquals(MessageStatus.SENT, mergeMessageVersions(listOf(sent), listOf(failed)).single().status)
    }

    @Test
    fun `plaintext wins over base64 wire envelope`() {
        val readable = message("m1", "hello")
        val wire = message("m1", "eyJ2ZXJzaW9uIjoxLCJhbGdvcml0aG0iOiJzaWduYWwifQ==")

        assertEquals("hello", mergeMessageVersions(listOf(readable), listOf(wire)).single().content)
        assertEquals("hello", mergeMessageVersions(listOf(wire), listOf(readable)).single().content)
    }

    @Test
    fun `padded placeholder is still recognized as placeholder`() {
        val readable = message("m1", "hello")
        val padded = message("m1", "  无法解密  ")

        assertEquals("hello", mergeMessageVersions(listOf(readable), listOf(padded)).single().content)
        assertEquals("hello", mergeMessageVersions(listOf(padded), listOf(readable)).single().content)
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
