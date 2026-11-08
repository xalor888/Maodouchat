package com.maodouchat.domain.messaging

import com.maodouchat.core.model.DecodedContentPayload
import com.maodouchat.core.model.EncryptedAttachmentReference
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MessageContentProjectionTest {

    private val reference = EncryptedAttachmentReference(
        attachmentId = "att-1",
        keyBase64 = "a2V5",
        ivBase64 = "aXY=",
        cipherSha256 = "cipher-sha",
        plainSha256 = "plain-sha",
        cipherSize = 2048L,
        fileName = "photo.jpg",
        mimeType = "image/jpeg",
        plainSize = 1024L,
        durationMs = 3000L,
    )

    private fun payload(type: MessageType, body: String, metadata: MessageMeta = MessageMeta()) =
        DecodedContentPayload(type = type, body = body, metadata = metadata)

    @Test
    fun `non attachment type returns body without decoding`() {
        var decoded = false
        val result = MessageContentProjection.projectContent(
            payload(MessageType.TEXT, "hello"),
            decodeReference = { decoded = true; reference },
            attachmentUri = { "maodou-attachment://$it" },
        )

        assertEquals("hello", result)
        assertFalse(decoded)
    }

    @Test
    fun `non attachment type returns metadata untouched`() {
        val metadata = MessageMeta(replyToId = "msg-0", mentions = listOf("bob"))
        val result = MessageContentProjection.projectMetadata(
            payload(MessageType.TEXT, "hello", metadata),
            decodeReference = { reference },
        )

        assertEquals(metadata, result)
    }

    @Test
    fun `attachment type without decodable reference returns body as is`() {
        val body = "{\"not\":\"a-reference\"}"
        val result = MessageContentProjection.projectContent(
            payload(MessageType.IMAGE, body),
            decodeReference = { null },
            attachmentUri = { "maodou-attachment://$it" },
        )

        assertEquals(body, result)
    }

    @Test
    fun `attachment type with reference returns attachment uri`() {
        val result = MessageContentProjection.projectContent(
            payload(MessageType.VIDEO, "{\"kind\":\"maodouchat-attachment-v1\"}"),
            decodeReference = { reference },
            attachmentUri = { "maodou-attachment://$it" },
        )

        assertEquals("maodou-attachment://att-1", result)
    }

    @Test
    fun `attachment metadata without decodable reference stays untouched`() {
        val metadata = MessageMeta(fileName = "old.jpg")
        val result = MessageContentProjection.projectMetadata(
            payload(MessageType.FILE, "plain-body", metadata),
            decodeReference = { null },
        )

        assertEquals(metadata, result)
    }

    @Test
    fun `attachment metadata is enriched from reference`() {
        val metadata = MessageMeta(replyToId = "msg-0")
        val result = MessageContentProjection.projectMetadata(
            payload(MessageType.VOICE, "{\"kind\":\"maodouchat-attachment-v1\"}", metadata),
            decodeReference = { reference },
        )

        assertEquals("photo.jpg", result.fileName)
        assertEquals("image/jpeg", result.fileMimeType)
        assertEquals(1024L, result.fileSizeBytes)
        assertEquals("att-1", result.attachmentId)
        assertEquals("a2V5", result.attachmentKeyBase64)
        assertEquals("aXY=", result.attachmentIvBase64)
        assertEquals("cipher-sha", result.attachmentCipherSha256)
        assertEquals("plain-sha", result.attachmentPlainSha256)
        assertEquals(2048L, result.attachmentCipherSize)
        assertEquals(3000L, result.voiceDurationMs)
        assertEquals("msg-0", result.replyToId)
    }
}
