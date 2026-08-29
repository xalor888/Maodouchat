package com.maodouchat.ui.component

import com.maodouchat.data.model.InlineKeyboardButton
import com.maodouchat.data.model.LocationPayload
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageReaction
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.JsonFormat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePresentationMapperTest {
    @Test
    fun `maps encoded body and metadata without exposing meta payload`() {
        val meta = MessageMeta(
            mentions = listOf("u2"),
            forwardedFrom = "Alice",
            voiceTranscript = "transcript",
            voiceDurationMs = 4_200L,
            markdown = true,
            aiAssisted = true,
            silent = true,
            inlineKeyboard = listOf(listOf(InlineKeyboardButton("Open", "open:1"))),
        )
        val message = message(
            type = MessageType.TEXT,
            content = "hello<meta>${JsonFormat.encodeMessageMeta(meta)}</meta>",
            meta = MessageMeta(),
            reactions = listOf(MessageReaction("me", "ok", 42L)),
        )

        val result = MessagePresentationMapper.map(message)

        assertEquals("hello", result.body)
        assertFalse(result.body.contains("attachmentKeyBase64"))
        assertEquals(listOf("u2"), result.meta.mentions)
        assertEquals("Alice", result.meta.forwardedFrom)
        assertEquals("transcript", result.meta.voiceTranscript)
        assertEquals(4_200L, result.meta.voiceDurationMs)
        assertTrue(result.meta.markdown)
        assertTrue(result.meta.aiAssisted)
        assertTrue(result.meta.silent)
        assertEquals("open:1", result.meta.inlineKeyboard.single().single().callbackData)
        assertEquals(MessageReactionPresentation("me", "ok", 42L), result.reactions.single())
        assertNull(result.attachment)
        assertNull(result.file)
    }

    @Test
    fun `maps attachment guards and file fallback name`() {
        val message = message(
            type = MessageType.FILE,
            content = "https://example.test/files/report.pdf",
            meta = MessageMeta(
                attachmentId = "att-1",
                fileMimeType = "application/pdf",
                fileSizeBytes = 2048L,
                viewOnce = true,
                spoilerMedia = true,
            ),
        )

        val result = MessagePresentationMapper.map(message)

        assertEquals("att-1", result.attachment?.attachmentId)
        assertFalse(result.attachment?.viewOnce == true)
        assertTrue(result.attachment?.spoiler == true)
        assertEquals("report.pdf", result.file?.name)
        assertEquals("application/pdf", result.file?.mimeType)
        assertEquals(2048L, result.file?.sizeBytes)
    }

    @Test
    fun `maps valid location and drops invalid payload`() {
        val valid = LocationPayload(31.2, 121.5, accuracyMeters = 8f, label = "Office", live = true, liveUntil = 123L)
        val validMessage = message(
            type = MessageType.LOCATION,
            content = Json.encodeToString(valid),
        )
        val invalidMessage = message(type = MessageType.LOCATION, content = "not-json")

        assertEquals("Office", MessagePresentationMapper.map(validMessage).location?.label)
        assertEquals(121.5, MessagePresentationMapper.map(validMessage).location?.longitude)
        assertNull(MessagePresentationMapper.map(invalidMessage).location)
    }

    @Test
    fun `flags unreadable text envelope for placeholder rendering`() {
        val wire = message(
            type = MessageType.TEXT,
            content = """{"version":1,"algorithm":"signal-sender-key-v1","ciphertext":"abc"}""",
        )

        assertTrue(MessagePresentationMapper.map(wire).requiresDecryptPlaceholder)
        assertFalse(MessagePresentationMapper.map(message(content = "normal")).requiresDecryptPlaceholder)
    }

    private fun message(
        type: MessageType = MessageType.TEXT,
        content: String,
        meta: MessageMeta = MessageMeta(),
        reactions: List<MessageReaction> = emptyList(),
    ) = Message(
        id = "m1",
        chatId = "c1",
        senderId = "u1",
        content = content,
        type = type,
        timestamp = 1_000L,
        meta = meta,
        reactions = reactions,
    )
}
