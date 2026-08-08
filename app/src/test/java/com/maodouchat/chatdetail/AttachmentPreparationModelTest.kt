package com.maodouchat.chatdetail

import com.maodouchat.data.model.MessageType
import com.maodouchat.ui.screen.chatdetail.normalizeAttachmentMetadata
import com.maodouchat.util.MediaCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AttachmentPreparationModelTest {
    @Test
    fun `typed media receives canonical extension and mime`() {
        assertMetadata(MessageType.IMAGE, "photo.png", "image/png", "photo.jpg", "image/jpeg")
        assertMetadata(MessageType.GIF, "dance.bin", "application/octet-stream", "dance.gif", "image/gif")
        assertMetadata(MessageType.VOICE, "recording.any", "audio/unknown", "voice.m4a", "audio/mp4")
        assertMetadata(MessageType.VIDEO, "clip.exe", "video/mp4", "clip.mp4", "video/mp4")
        assertMetadata(MessageType.VIDEO, "clip.webm", "application/octet-stream", "clip.webm", "video/webm")
    }

    @Test
    fun `mismatched video extension follows supported declared mime`() {
        assertMetadata(MessageType.VIDEO, "clip.webm", "video/quicktime", "clip.mov", "video/quicktime")
    }

    @Test
    fun `file metadata is sanitized and malformed mime fails closed`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.FILE,
            MediaCache.LocalFileMetadata("../bad\u0000:name?.pdf", "Text/Plain\r\nX-Evil: yes", -8L)
        )

        assertFalse(normalized.fileName.any { it.isISOControl() || it in "\\/:*?\"<>|" })
        assertEquals("application/octet-stream", normalized.mimeType)
        assertEquals(0L, normalized.sizeBytes)
    }

    private fun assertMetadata(
        type: MessageType,
        name: String,
        mime: String,
        expectedName: String,
        expectedMime: String
    ) {
        val normalized = normalizeAttachmentMetadata(type, MediaCache.LocalFileMetadata(name, mime, 12L))
        assertEquals(expectedName, normalized.fileName)
        assertEquals(expectedMime, normalized.mimeType)
        assertEquals(12L, normalized.sizeBytes)
    }
}
