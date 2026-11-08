package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutboxAttachmentTypesTest {

    @Test
    fun `media and file types carry attachment references`() {
        assertTrue(isAttachmentMessageType(MessageType.IMAGE))
        assertTrue(isAttachmentMessageType(MessageType.GIF))
        assertTrue(isAttachmentMessageType(MessageType.VIDEO))
        assertTrue(isAttachmentMessageType(MessageType.VOICE))
        assertTrue(isAttachmentMessageType(MessageType.FILE))
    }

    @Test
    fun `text and event types do not`() {
        assertFalse(isAttachmentMessageType(MessageType.TEXT))
        assertFalse(isAttachmentMessageType(MessageType.MARKDOWN))
        assertFalse(isAttachmentMessageType(MessageType.STICKER))
        assertFalse(isAttachmentMessageType(MessageType.LOCATION))
        assertFalse(isAttachmentMessageType(MessageType.NUDGE))
        assertFalse(isAttachmentMessageType(MessageType.SK_DIST))
        assertFalse(isAttachmentMessageType(MessageType.SYSTEM))
        assertFalse(isAttachmentMessageType(MessageType.REVOKED))
    }
}
