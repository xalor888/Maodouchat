package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageContentTest {

    private fun text(content: String) = Message(
        id = "m1", chatId = "c1", senderId = "u1",
        content = content, type = MessageType.TEXT,
    )

    @Test
    fun `parsedContent strips trailing meta tag`() {
        val meta = MessageMetaJson.encodeMessageMeta(MessageMeta(replyToId = "m0"))
        assertEquals("hello", text("hello<meta>$meta</meta>").parsedContent())
        assertEquals("plain", text("plain").parsedContent())
    }

    @Test
    fun `parsedMeta decodes meta appended to content`() {
        val meta = MessageMetaJson.encodeMessageMeta(MessageMeta(replyToId = "m0"))
        assertEquals("m0", text("hi<meta>$meta</meta>").parsedMeta().replyToId)
        assertEquals(MessageMeta(), text("hi").parsedMeta())
    }
}
