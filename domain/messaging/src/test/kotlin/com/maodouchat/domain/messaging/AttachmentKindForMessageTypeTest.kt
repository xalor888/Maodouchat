package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AttachmentKindForMessageTypeTest {

    @Test
    fun `the five mapped types keep their kinds`() {
        assertEquals(AttachmentKind.IMAGE, attachmentKindForMessageType(MessageType.IMAGE))
        assertEquals(AttachmentKind.VIDEO, attachmentKindForMessageType(MessageType.VIDEO))
        assertEquals(AttachmentKind.VOICE, attachmentKindForMessageType(MessageType.VOICE))
        assertEquals(AttachmentKind.FILE, attachmentKindForMessageType(MessageType.FILE))
        assertEquals(AttachmentKind.GIF, attachmentKindForMessageType(MessageType.GIF))
    }

    @Test
    fun `text falls back to file`() {
        assertEquals(AttachmentKind.FILE, attachmentKindForMessageType(MessageType.TEXT))
    }

    @Test
    fun `sticker and location fall back to file`() {
        assertEquals(AttachmentKind.FILE, attachmentKindForMessageType(MessageType.STICKER))
        assertEquals(AttachmentKind.FILE, attachmentKindForMessageType(MessageType.LOCATION))
    }
}
