package com.maodouchat.domain.messaging

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContentPayloadTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `text with mention round trips`() {
        val original = ContentEnvelope(
            content = ContentPayload.Text("hello @Alice", listOf(Mention("u1", "Alice"))),
        )
        val decoded = json.decodeFromString<ContentEnvelope>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `reply and attachment round trip`() {
        val original = ContentEnvelope(
            content = ContentPayload.Reply(
                replyToMessageId = "m_123",
                content = ContentPayload.Attachment(
                    kind = AttachmentKind.IMAGE,
                    attachmentId = "att_abc",
                    fileName = "pic.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 4096,
                ),
            ),
        )
        val decoded = json.decodeFromString<ContentEnvelope>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `unknown fields are ignored for forward compatibility`() {
        val raw = """{"version":1,"content":{"type":"text","text":"hi","futureField":123}}"""
        val decoded = json.decodeFromString<ContentEnvelope>(raw)
        assertEquals(ContentPayload.Text("hi"), decoded.content)
    }

    @Test
    fun `wire discriminator is stable`() {
        val encoded = json.encodeToString<ContentPayload>(ContentPayload.Poll("p1", "q?", listOf("a", "b")))
        assertTrue("\"type\":\"poll\"" in encoded)
    }

    @Test
    fun `unknown type degrades to Unknown fallback instead of throwing`() {
        val raw = """{"version":1,"content":{"type":"futureType_v9","someField":42}}"""
        val decoded = decodeContentEnvelopeFailSafe(json, raw)
        val content = decoded.content
        assertTrue(content is ContentPayload.Unknown, "expected Unknown, got $content")
        content as ContentPayload.Unknown
        assertEquals("futureType_v9", content.rawType)
        assertTrue(content.rawPayload.contains("someField"), content.rawPayload)
    }
}
