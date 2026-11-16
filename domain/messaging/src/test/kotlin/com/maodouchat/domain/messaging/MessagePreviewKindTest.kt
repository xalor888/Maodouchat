package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MessagePreviewKindTest {

    @Test
    fun `nine media types map to their own kinds`() {
        assertEquals(MessagePreviewKind.IMAGE, messagePreviewKind(MessageType.IMAGE))
        assertEquals(MessagePreviewKind.GIF, messagePreviewKind(MessageType.GIF))
        assertEquals(MessagePreviewKind.STICKER, messagePreviewKind(MessageType.STICKER))
        assertEquals(MessagePreviewKind.LOCATION, messagePreviewKind(MessageType.LOCATION))
        assertEquals(MessagePreviewKind.VOICE, messagePreviewKind(MessageType.VOICE))
        assertEquals(MessagePreviewKind.VIDEO, messagePreviewKind(MessageType.VIDEO))
        assertEquals(MessagePreviewKind.FILE, messagePreviewKind(MessageType.FILE))
        assertEquals(MessagePreviewKind.NUDGE, messagePreviewKind(MessageType.NUDGE))
        assertEquals(MessagePreviewKind.SYSTEM, messagePreviewKind(MessageType.SYSTEM))
    }

    @Test
    fun `other types share the OTHER bucket`() {
        assertEquals(MessagePreviewKind.OTHER, messagePreviewKind(MessageType.TEXT))
        assertEquals(MessagePreviewKind.OTHER, messagePreviewKind(MessageType.MARKDOWN))
        assertEquals(MessagePreviewKind.OTHER, messagePreviewKind(MessageType.SK_DIST))
        assertEquals(MessagePreviewKind.OTHER, messagePreviewKind(MessageType.REVOKED))
    }
}
