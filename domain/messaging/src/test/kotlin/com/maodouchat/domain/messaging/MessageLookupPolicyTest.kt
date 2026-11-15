package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MessageLookupPolicyTest {

    private fun message(id: String, content: String = "hi") =
        Message(id = id, chatId = "c1", senderId = "u1", content = content, timestamp = 1L)

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
}
