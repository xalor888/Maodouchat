package com.maodouchat.chatdetail

import com.maodouchat.data.model.MessageType
import com.maodouchat.attachment.isAttachmentContentCompatible
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentContentInspectorTest {
    @Test
    fun `jpeg and gif signatures match only their message types`() {
        val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0)
        val gif = "GIF89a".encodeToByteArray()

        assertTrue(isAttachmentContentCompatible(MessageType.IMAGE, jpeg))
        assertFalse(isAttachmentContentCompatible(MessageType.GIF, jpeg))
        assertTrue(isAttachmentContentCompatible(MessageType.GIF, gif))
        assertFalse(isAttachmentContentCompatible(MessageType.IMAGE, gif))
    }

    @Test
    fun `iso media and ebml signatures cover supported video containers`() {
        val isoMedia = bytes(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D)
        val ebml = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x93, 0x42)

        assertTrue(isAttachmentContentCompatible(MessageType.VIDEO, isoMedia))
        assertTrue(isAttachmentContentCompatible(MessageType.VOICE, isoMedia))
        assertTrue(isAttachmentContentCompatible(MessageType.VIDEO, ebml))
        assertFalse(isAttachmentContentCompatible(MessageType.VOICE, ebml))
    }

    @Test
    fun `disguised media is rejected while generic files remain allowed`() {
        val arbitrary = "not really a video".encodeToByteArray()

        assertFalse(isAttachmentContentCompatible(MessageType.VIDEO, arbitrary))
        assertFalse(isAttachmentContentCompatible(MessageType.GIF, arbitrary))
        assertTrue(isAttachmentContentCompatible(MessageType.FILE, arbitrary))
    }

    private fun bytes(vararg values: Int): ByteArray = values.map(Int::toByte).toByteArray()
}
