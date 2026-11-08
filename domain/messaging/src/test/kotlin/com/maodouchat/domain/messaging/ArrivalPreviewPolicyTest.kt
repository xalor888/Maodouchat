package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArrivalPreviewPolicyTest {

    private fun message(type: MessageType, content: String = "body") =
        Message(id = "m1", chatId = "c1", senderId = "me", content = content, type = type)

    @Test
    fun `text markdown and system show truncated body`() {
        for (type in listOf(MessageType.TEXT, MessageType.MARKDOWN, MessageType.SYSTEM)) {
            val preview = ArrivalPreviewPolicy.preview(message(type, "hello"))
            assertTrue(preview is ArrivalPreview.Text)
            assertEquals("hello", (preview as ArrivalPreview.Text).text)
        }
    }

    @Test
    fun `long body is truncated to 500 chars`() {
        val preview = ArrivalPreviewPolicy.preview(message(MessageType.TEXT, "x".repeat(600)))
        assertTrue(preview is ArrivalPreview.Text)
        assertEquals("x".repeat(500), (preview as ArrivalPreview.Text).text)
    }

    @Test
    fun `media types map to their own preview kind`() {
        assertEquals(ArrivalPreview.Image, ArrivalPreviewPolicy.preview(message(MessageType.IMAGE)))
        assertEquals(ArrivalPreview.Gif, ArrivalPreviewPolicy.preview(message(MessageType.GIF)))
        assertEquals(ArrivalPreview.Sticker, ArrivalPreviewPolicy.preview(message(MessageType.STICKER)))
        assertEquals(ArrivalPreview.Location, ArrivalPreviewPolicy.preview(message(MessageType.LOCATION)))
        assertEquals(ArrivalPreview.Voice, ArrivalPreviewPolicy.preview(message(MessageType.VOICE)))
        assertEquals(ArrivalPreview.Video, ArrivalPreviewPolicy.preview(message(MessageType.VIDEO)))
        assertEquals(ArrivalPreview.File, ArrivalPreviewPolicy.preview(message(MessageType.FILE)))
        assertEquals(ArrivalPreview.Nudge, ArrivalPreviewPolicy.preview(message(MessageType.NUDGE)))
    }

    @Test
    fun `revoked and sender key distribution show as encrypted`() {
        assertEquals(ArrivalPreview.Encrypted, ArrivalPreviewPolicy.preview(message(MessageType.REVOKED)))
        assertEquals(ArrivalPreview.Encrypted, ArrivalPreviewPolicy.preview(message(MessageType.SK_DIST)))
    }
}
