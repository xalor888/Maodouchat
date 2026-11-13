package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForwardPreviewTextTest {

    @Test
    fun `media types map to their preview label`() {
        assertEquals(ChatMessagePreviewText.PREVIEW_IMAGE, forwardPreviewText(MessageType.IMAGE))
        assertEquals(ChatMessagePreviewText.PREVIEW_GIF, forwardPreviewText(MessageType.GIF))
        assertEquals(ChatMessagePreviewText.PREVIEW_STICKER, forwardPreviewText(MessageType.STICKER))
        assertEquals(ChatMessagePreviewText.PREVIEW_LOCATION, forwardPreviewText(MessageType.LOCATION))
        assertEquals(ChatMessagePreviewText.PREVIEW_VIDEO, forwardPreviewText(MessageType.VIDEO))
        assertEquals(ChatMessagePreviewText.PREVIEW_VOICE, forwardPreviewText(MessageType.VOICE))
        assertEquals(ChatMessagePreviewText.PREVIEW_FILE, forwardPreviewText(MessageType.FILE))
    }

    @Test
    fun `non-media types have no preview label`() {
        assertNull(forwardPreviewText(MessageType.TEXT))
        assertNull(forwardPreviewText(MessageType.MARKDOWN))
        assertNull(forwardPreviewText(MessageType.NUDGE))
        assertNull(forwardPreviewText(MessageType.SK_DIST))
        assertNull(forwardPreviewText(MessageType.SYSTEM))
        assertNull(forwardPreviewText(MessageType.REVOKED))
    }
}
