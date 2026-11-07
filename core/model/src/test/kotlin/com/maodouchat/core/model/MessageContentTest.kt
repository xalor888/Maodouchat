package com.maodouchat.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `withEncodedMeta round trips through parsedMeta`() {
        val msg = text("hello").withEncodedMeta(MessageMeta(forwardedFrom = "Alice"))
        assertEquals("hello", msg.parsedContent())
        assertEquals("Alice", msg.parsedMeta().forwardedFrom)
    }

    @Test
    fun `parsedLocation accepts valid payload and rejects bad latitude`() {
        val payload = LocationPayload(latitude = 31.2, longitude = 121.5, label = "外滩")
        val body = Json.encodeToString(payload)
        val loc = Message(
            id = "m2", chatId = "c1", senderId = "u1",
            content = body, type = MessageType.LOCATION,
        )
        assertEquals("外滩", loc.parsedLocation()?.label)
        val bad = Message(
            id = "m3", chatId = "c1", senderId = "u1",
            content = "{\"latitude\": 91.0, \"longitude\": 0.0}",
            type = MessageType.LOCATION,
        )
        assertNull(bad.parsedLocation())
        assertNull(text(body).parsedLocation())
    }
}
