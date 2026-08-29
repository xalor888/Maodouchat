package com.maodouchat.messaging.v2

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentPayloadCodecTest {
    @Test
    fun `structured encoding writes metadata outside display body`() {
        val payload = ContentPayload(
            type = MessageType.TEXT,
            body = "hello",
            metadata = MessageMeta(forwardedFrom = "Alice", mentions = listOf("user-1")),
        )

        val encoded = ContentPayloadCodec.encode(payload)

        assertEquals(2, encoded.version)
        assertEquals("hello", encoded.body)
        assertEquals("Alice", encoded.metadata?.forwardedFrom)
        assertFalse(encoded.body.contains(Message.META_TAG_PREFIX))
        assertEquals(listOf("user-1"), encoded.mentionedUserIds)
    }

    @Test
    fun `legacy marker is read into typed metadata`() {
        val legacy = MessagingV2Content(
            type = "TEXT",
            body = "hello<meta>{\"forwardedFrom\":\"Alice\"}</meta>",
        )

        val decoded = ContentPayloadCodec.decode(legacy)

        assertEquals("hello", decoded.body)
        assertEquals("Alice", decoded.metadata.forwardedFrom)
    }

    @Test
    fun `normalizing legacy local message removes marker and retains metadata`() {
        val legacy = Message(
            id = "message-1",
            chatId = "chat-1",
            senderId = "owner-1",
            content = "hello<meta>{\"replyToId\":\"source-1\"}</meta>",
        )

        val normalized = ContentPayloadCodec.normalizeLocalMessage(legacy)

        assertEquals("hello", normalized.content)
        assertEquals("source-1", normalized.meta.replyToId)
        assertTrue(!normalized.content.contains(Message.META_TAG_PREFIX))
    }
}
