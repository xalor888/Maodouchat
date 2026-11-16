package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MessageSharePreviewKindTest {

    @Test
    fun `the four mapped types keep their kinds`() {
        assertEquals(MessageSharePreviewKind.IMAGE, messageSharePreviewKind(MessageType.IMAGE))
        assertEquals(MessageSharePreviewKind.GIF, messageSharePreviewKind(MessageType.GIF))
        assertEquals(MessageSharePreviewKind.STICKER, messageSharePreviewKind(MessageType.STICKER))
        assertEquals(MessageSharePreviewKind.LOCATION, messageSharePreviewKind(MessageType.LOCATION))
    }

    @Test
    fun `other types fall back to file`() {
        assertEquals(MessageSharePreviewKind.FILE, messageSharePreviewKind(MessageType.TEXT))
        assertEquals(MessageSharePreviewKind.FILE, messageSharePreviewKind(MessageType.VIDEO))
        assertEquals(MessageSharePreviewKind.FILE, messageSharePreviewKind(MessageType.VOICE))
        assertEquals(MessageSharePreviewKind.FILE, messageSharePreviewKind(MessageType.FILE))
    }
}
