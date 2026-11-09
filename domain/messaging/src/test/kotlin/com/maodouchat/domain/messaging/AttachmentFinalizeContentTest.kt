package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentFinalizeContentTest {

    private val meta = MessageMeta(
        fileName = "photo.jpg",
        fileMimeType = "image/jpeg",
        fileSizeBytes = 1234L,
        attachmentId = "att-1",
        attachmentKeyBase64 = "a2V5",
    )

    @Test
    fun `shape is text plus meta segment`() {
        val content = composeFinalizeContent("https://example/x", meta)
        assertTrue(content.startsWith("https://example/x<meta>"))
        assertTrue(content.endsWith("</meta>"))
        assertTrue(content.contains("\"fileName\":\"photo.jpg\""))
    }

    @Test
    fun `round trips through parsedMeta`() {
        val message = Message(
            id = "m1",
            chatId = "c1",
            senderId = "me",
            content = composeFinalizeContent("uri", meta),
            type = MessageType.IMAGE,
        )
        val parsed = message.parsedMeta()
        assertEquals("photo.jpg", parsed.fileName)
        assertEquals("image/jpeg", parsed.fileMimeType)
        assertEquals(1234L, parsed.fileSizeBytes)
        assertEquals("att-1", parsed.attachmentId)
        assertEquals("a2V5", parsed.attachmentKeyBase64)
        assertEquals("uri", message.parsedContent())
    }

    @Test
    fun `default meta still keeps the meta segment`() {
        val content = composeFinalizeContent("x", MessageMeta())
        assertTrue(content.startsWith("x<meta>"))
        assertTrue(content.endsWith("</meta>"))
    }

    @Test
    fun `meta literal in text is kept verbatim`() {
        val content = composeFinalizeContent("a<meta>b", meta)
        assertTrue(content.startsWith("a<meta>b<meta>"))
    }
}
