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

    @Test
    fun `location and contact round trip`() {
        val original = ContentEnvelope(content = ContentPayload.Location(31.23, 121.47))
        assertEquals(original, json.decodeFromString<ContentEnvelope>(json.encodeToString(original)))
        val contact = ContentEnvelope(content = ContentPayload.Contact("u2", "Bob"))
        assertEquals(contact, json.decodeFromString<ContentEnvelope>(json.encodeToString(contact)))
    }

    @Test
    fun `attachment with null defaults round trips`() {
        val original = ContentEnvelope(
            content = ContentPayload.Attachment(kind = AttachmentKind.FILE, attachmentId = "att_x"),
        )
        val decoded = json.decodeFromString<ContentEnvelope>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `system event wire discriminator is stable`() {
        val encoded = json.encodeToString<ContentPayload>(ContentPayload.SystemEvent("join"))
        assertTrue("\"type\":\"system\"" in encoded)
        val roundTripped = json.decodeFromString<ContentPayload>(encoded)
        assertEquals(ContentPayload.SystemEvent("join"), roundTripped)
    }

    @Test
    fun `garbage input degrades to unknown preserving raw`() {
        val decoded = decodeContentEnvelopeFailSafe(json, "not json at all")
        val content = decoded.content
        assertTrue(content is ContentPayload.Unknown, "expected Unknown, got $content")
        content as ContentPayload.Unknown
        assertEquals("unknown", content.rawType)
        assertEquals("not json at all", content.rawPayload)
        assertEquals(1, decoded.version)
    }

    @Test
    fun `nested unknown type inside reply degrades envelope to reply type`() {
        val raw = """{"version":1,"content":{"type":"reply","replyToMessageId":"m1","content":{"type":"future_v9","x":1}}}"""
        val decoded = decodeContentEnvelopeFailSafe(json, raw)
        val content = decoded.content
        assertTrue(content is ContentPayload.Unknown, "expected Unknown, got $content")
        content as ContentPayload.Unknown
        assertEquals("reply", content.rawType)
        assertTrue(content.rawPayload.contains("future_v9"), content.rawPayload)
    }
}
