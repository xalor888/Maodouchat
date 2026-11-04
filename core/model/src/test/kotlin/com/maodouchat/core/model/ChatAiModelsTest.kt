package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAiModelsTest {

    @Test
    fun `prepared ai file carries all fields`() {
        val file = PreparedAiFile(base64 = "abc", fileName = "a.png", mimeType = "image/png")
        assertEquals("abc", file.base64)
        assertEquals("a.png", file.fileName)
        assertEquals("image/png", file.mimeType)
    }

    @Test
    fun `request snapshot carries ids and copy keeps the other`() {
        val snapshot = AiRequestSnapshot(userId = "u1", chatId = "c1")
        assertEquals("u1", snapshot.userId)
        assertEquals("c1", snapshot.chatId)
        assertEquals("c2", snapshot.copy(chatId = "c2").chatId)
        assertEquals("u1", snapshot.copy(chatId = "c2").userId)
    }

    @Test
    fun `context senders carry labels and name map`() {
        val senders = AiContextSenders(
            currentUserId = "me",
            currentUserLabel = "\u6211",
            fallbackLabel = "\u5bf9\u65b9",
            namesByUserId = mapOf("alice" to "Alice")
        )
        assertEquals("me", senders.currentUserId)
        assertEquals("Alice", senders.namesByUserId["alice"])
    }
}
