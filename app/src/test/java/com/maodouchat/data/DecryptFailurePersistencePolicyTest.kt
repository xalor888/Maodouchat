package com.maodouchat.data

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.mergeMessageForPersistence
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * XAL-20：Room 回写不得用解密失败占位覆盖已明文。
 * 与 MessagePersistencePolicyTest 的密文 envelope 用例独立，避免与 XAL-16 文件冲突。
 */
class DecryptFailurePersistencePolicyTest {

    @Test
    fun `persisted plaintext is preferred over decrypt placeholder`() {
        val existing = base("m1").copy(content = "hello readable")
        val incoming = base("m1").copy(content = "无法解密")

        assertEquals("hello readable", mergeMessageForPersistence(existing, incoming).content)
        assertEquals("hello readable", mergeMessageForPersistence(incoming, existing).content)
    }

    @Test
    fun `persisted plaintext is preferred over english decrypt failed placeholder`() {
        val existing = base("m1").copy(content = "ok")
        val incoming = base("m1").copy(content = "decrypt failed")

        assertEquals("ok", mergeMessageForPersistence(existing, incoming).content)
    }

    @Test
    fun `persisted plaintext is preferred over group key missing placeholder`() {
        val existing = base("m1").copy(content = "群消息")
        val incoming = base("m1").copy(content = "群密钥缺失")

        assertEquals("群消息", mergeMessageForPersistence(existing, incoming).content)
    }

    @Test
    fun `sender key prefix is not treated as readable plaintext`() {
        val existing = base("m1").copy(content = "hello")
        val incoming = base("m1").copy(content = "SK:dist-payload")

        assertEquals("hello", mergeMessageForPersistence(existing, incoming).content)
    }

    @Test
    fun `placeholder must not overwrite stored group ciphertext`() {
        val wire = """{"version":1,"algorithm":"signal-sender-key","ciphertext":"abc","groupId":"g1"}"""
        val existing = base("m1").copy(content = wire)
        val incoming = base("m1").copy(content = "无法解密")

        assertEquals(wire, mergeMessageForPersistence(existing, incoming).content)
        assertEquals(wire, mergeMessageForPersistence(incoming, existing).content)
    }

    @Test
    fun `compact omitted-algorithm envelope is not replaced by decrypt placeholder`() {
        val wire =
            """{"senderDeviceId":76,"payloadType":"TEXT","entries":[{"ciphertextType":"prekey","ciphertext":"NAgB"}]}"""
        val existing = base("m1").copy(content = wire)
        val incoming = base("m1").copy(content = "decrypt failed")
        assertEquals(wire, mergeMessageForPersistence(existing, incoming).content)
        assertEquals(wire, mergeMessageForPersistence(incoming, existing).content)
    }

    @Test
    fun `ordinary json containing ciphertext field remains readable plaintext`() {
        val readable = base("json").copy(content = "{\"ciphertext\":\"this is a user field\",\"devices\":2}")
        val placeholder = base("json").copy(content = "无法解密")

        assertEquals(readable.content, mergeMessageForPersistence(readable, placeholder).content)
        assertEquals(readable.content, mergeMessageForPersistence(placeholder, readable).content)
    }

    @Test
    fun `decrypt related user plaintext wins over wire in both merge directions`() {
        val wire =
            """{"senderDeviceId":76,"payloadType":"TEXT","entries":[{"ciphertext":"NAgB"}]}"""
        listOf(
            "这个怎么解密？",
            "这个消息该怎么解密？",
            "decrypt this message",
            "How do I decrypt this?",
            "session missing 是什么意思？",
        ).forEachIndexed { index, plaintext ->
            val readable = base("m$index").copy(content = plaintext)
            val encrypted = base("m$index").copy(content = wire)

            assertEquals(plaintext, mergeMessageForPersistence(readable, encrypted).content)
            assertEquals(plaintext, mergeMessageForPersistence(encrypted, readable).content)
        }
    }

    private fun base(id: String) = Message(
        id = id,
        chatId = "c1",
        senderId = "u1",
        content = "text",
        type = MessageType.TEXT,
        timestamp = 1L,
        status = MessageStatus.SENT
    )
}
