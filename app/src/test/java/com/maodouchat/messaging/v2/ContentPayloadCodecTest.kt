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
        val payload = DecodedContentPayload(
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

    @Test
    fun `literal meta text without closing tag is preserved as display text`() {
        val legacy = MessagingV2Content(
            type = "TEXT",
            body = "note <meta> literal without close",
        )

        val decoded = ContentPayloadCodec.decode(legacy)

        // 字面 `<meta>` 无闭合 `</meta>`：整体按纯文本，不得截断后续内容，也不解析出 metadata。
        assertEquals("note <meta> literal without close", decoded.body)
        assertEquals(MessageMeta(), decoded.metadata)
    }

    @Test
    fun `literal meta with both tags parses metadata after last marker`() {
        val legacy = MessagingV2Content(
            type = "TEXT",
            body = "see <meta> then <meta>{\"forwardedFrom\":\"Bob\"}</meta>",
        )

        val decoded = ContentPayloadCodec.decode(legacy)

        assertEquals("see <meta> then ", decoded.body)
        assertEquals("Bob", decoded.metadata.forwardedFrom)
    }

    @Test
    fun `structured round trip preserves transport fields`() {
        val original = DecodedContentPayload(
            type = MessageType.IMAGE,
            body = "caption",
            metadata = MessageMeta(replyToId = "r1", mentions = listOf("u1"), attachmentId = "att-1"),
            replyToMessageId = "r1",
            attachmentIds = listOf("att-1"),
            mentionedUserIds = listOf("u1"),
            expiresInSeconds = 60,
            attributes = mapOf("w" to "800"),
        )

        val decoded = ContentPayloadCodec.decode(ContentPayloadCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `unknown wire type falls back to text`() {
        val decoded = ContentPayloadCodec.decode(
            MessagingV2Content(type = "FROBNICATOR", body = "hi"),
        )

        assertEquals(MessageType.TEXT, decoded.type)
    }

    @Test
    fun `top level reply and mentions win over legacy metadata`() {
        val content = MessagingV2Content(
            type = "TEXT",
            body = "hi",
            replyToMessageId = "r1",
            mentionedUserIds = listOf("u1"),
            metadata = MessageMeta(replyToId = "r0", mentions = listOf("u0")),
        )

        val decoded = ContentPayloadCodec.decode(content)

        assertEquals("r1", decoded.replyToMessageId)
        assertEquals(listOf("u1"), decoded.mentionedUserIds)
        assertEquals("r1", decoded.metadata.replyToId)
        assertEquals(listOf("u1"), decoded.metadata.mentions)
    }

    @Test
    fun `empty top level lists fall back to metadata`() {
        val content = MessagingV2Content(
            type = "FILE",
            body = "doc",
            metadata = MessageMeta(mentions = listOf("u2"), attachmentId = "att-2"),
        )

        val decoded = ContentPayloadCodec.decode(content)

        assertEquals(listOf("att-2"), decoded.attachmentIds)
        assertEquals(listOf("u2"), decoded.mentionedUserIds)
    }

    @Test
    fun `malformed legacy meta json truncates body with empty metadata`() {
        val decoded = ContentPayloadCodec.decode(
            MessagingV2Content(type = "TEXT", body = "hello<meta>{not json</meta>"),
        )

        assertEquals("hello", decoded.body)
        assertEquals(MessageMeta(), decoded.metadata)
    }
}
