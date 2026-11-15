package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MessageLookupPolicyTest {

    private fun message(id: String, content: String = "hi", type: MessageType = MessageType.TEXT) =
        Message(id = id, chatId = "c1", senderId = "u1", content = content, type = type, timestamp = 1L)

    @Test
    fun `finds middle message`() {
        val messages = listOf(message("m1"), message("m2"), message("m3"))
        assertEquals(message("m2"), MessageLookupPolicy.findById(messages, "m2"))
    }

    @Test
    fun `finds first message`() {
        val messages = listOf(message("m1"), message("m2"))
        assertEquals(message("m1"), MessageLookupPolicy.findById(messages, "m1"))
    }

    @Test
    fun `finds last message`() {
        val messages = listOf(message("m1"), message("m2"))
        assertEquals(message("m2"), MessageLookupPolicy.findById(messages, "m2"))
    }

    @Test
    fun `unknown id returns null`() {
        val messages = listOf(message("m1"), message("m2"))
        assertNull(MessageLookupPolicy.findById(messages, "m9"))
    }

    @Test
    fun `empty list returns null`() {
        assertNull(MessageLookupPolicy.findById(emptyList<Message>(), "m1"))
    }

    @Test
    fun `findByIdAndType finds text by single type`() {
        val messages = listOf(message("m1", type = MessageType.IMAGE), message("m2"))
        assertEquals(message("m2"), MessageLookupPolicy.findByIdAndType(messages, "m2", MessageType.TEXT))
    }

    @Test
    fun `findByIdAndType returns null on type mismatch`() {
        val messages = listOf(message("m1", type = MessageType.IMAGE))
        assertNull(MessageLookupPolicy.findByIdAndType(messages, "m1", MessageType.VOICE))
    }

    @Test
    fun `findByIdAndType finds markdown with dual types`() {
        val messages = listOf(message("m1", type = MessageType.MARKDOWN))
        assertEquals(
            message("m1", type = MessageType.MARKDOWN),
            MessageLookupPolicy.findByIdAndType(messages, "m1", MessageType.TEXT, MessageType.MARKDOWN)
        )
    }

    @Test
    fun `findByIdAndType returns null on id mismatch`() {
        val messages = listOf(message("m1"))
        assertNull(MessageLookupPolicy.findByIdAndType(messages, "m9", MessageType.TEXT))
    }

    @Test
    fun `findByIdAndType returns null for empty list`() {
        assertNull(MessageLookupPolicy.findByIdAndType(emptyList<Message>(), "m1", MessageType.TEXT))
    }

    @Test
    fun `findByIdAndType returns first duplicate with matching type`() {
        val messages = listOf(
            message("m1", content = "image", type = MessageType.IMAGE),
            message("m1", content = "text", type = MessageType.TEXT)
        )
        assertEquals(
            message("m1", content = "text", type = MessageType.TEXT),
            MessageLookupPolicy.findByIdAndType(messages, "m1", MessageType.TEXT)
        )
    }
}
