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

    @Test
    fun `gif87a variant is accepted as gif`() {
        assertTrue(isAttachmentContentCompatible(MessageType.GIF, "GIF87a".encodeToByteArray()))
        assertFalse(isAttachmentContentCompatible(MessageType.IMAGE, "GIF87a".encodeToByteArray()))
    }

    @Test
    fun `truncated headers match nothing except generic files`() {
        val shortJpeg = bytes(0xFF, 0xD8)
        assertFalse(isAttachmentContentCompatible(MessageType.IMAGE, shortJpeg))
        assertFalse(isAttachmentContentCompatible(MessageType.VIDEO, bytes(0x1A, 0x45)))
        assertTrue(isAttachmentContentCompatible(MessageType.FILE, shortJpeg))
        assertTrue(isAttachmentContentCompatible(MessageType.FILE, ByteArray(0)))
    }

    @Test
    fun `voice accepts only iso base media`() {
        val ebml = bytes(0x1A, 0x45, 0xDF, 0xA3)
        assertFalse(isAttachmentContentCompatible(MessageType.VOICE, ebml))
        assertFalse(isAttachmentContentCompatible(MessageType.VOICE, "GIF89a".encodeToByteArray()))
    }

    @Test
    fun `near miss jpeg header is rejected`() {
        assertFalse(isAttachmentContentCompatible(MessageType.IMAGE, bytes(0xFF, 0xD8, 0xE0)))
    }

    @Test
    fun `non media message types reject everything but files`() {
        val jpeg = bytes(0xFF, 0xD8, 0xFF)
        assertFalse(isAttachmentContentCompatible(MessageType.STICKER, jpeg))
        assertFalse(isAttachmentContentCompatible(MessageType.TEXT, jpeg))
        assertFalse(isAttachmentContentCompatible(MessageType.LOCATION, ByteArray(0)))
    }

    @Test
    fun `png and webp are rejected as image and video`() {
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertFalse(isAttachmentContentCompatible(MessageType.IMAGE, png))
        assertFalse(isAttachmentContentCompatible(MessageType.VIDEO, png))

        val webp = "RIFF".encodeToByteArray() + bytes(0x24, 0x00, 0x00, 0x00) + "WEBP".encodeToByteArray()
        assertFalse(isAttachmentContentCompatible(MessageType.IMAGE, webp))
        assertFalse(isAttachmentContentCompatible(MessageType.VIDEO, webp))
    }

    @Test
    fun `iso brand needs eight bytes at fixed offsets`() {
        val short = bytes(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79)
        assertFalse(isAttachmentContentCompatible(MessageType.VIDEO, short))
        assertFalse(isAttachmentContentCompatible(MessageType.VOICE, short))

        val shifted = bytes(0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69)
        assertFalse(isAttachmentContentCompatible(MessageType.VIDEO, shifted))
        assertFalse(isAttachmentContentCompatible(MessageType.VOICE, shifted))
    }

    @Test
    fun `voice rejects mp3 and riff headers`() {
        val id3 = "ID3".encodeToByteArray() + bytes(0x04, 0x00)
        assertFalse(isAttachmentContentCompatible(MessageType.VOICE, id3))
        assertFalse(isAttachmentContentCompatible(MessageType.VIDEO, id3))
    }

    @Test
    fun `exact boundary prefix still matches`() {
        assertTrue(isAttachmentContentCompatible(MessageType.IMAGE, bytes(0xFF, 0xD8, 0xFF)))
        assertTrue(isAttachmentContentCompatible(MessageType.VIDEO, bytes(0x1A, 0x45, 0xDF, 0xA3)))
        assertTrue(isAttachmentContentCompatible(MessageType.GIF, "GIF89a".encodeToByteArray()))
    }

    @Test
    fun `gif prefix is case sensitive`() {
        assertFalse(isAttachmentContentCompatible(MessageType.GIF, "gif89a".encodeToByteArray()))
        assertFalse(isAttachmentContentCompatible(MessageType.GIF, "GIF89A".encodeToByteArray()))
    }

    private fun bytes(vararg values: Int): ByteArray = values.map(Int::toByte).toByteArray()
}
