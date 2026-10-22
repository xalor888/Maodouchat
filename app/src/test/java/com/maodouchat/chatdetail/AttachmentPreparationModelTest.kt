package com.maodouchat.chatdetail

import com.maodouchat.data.model.MessageType
import com.maodouchat.attachment.normalizeAttachmentMetadata
import com.maodouchat.util.LocalFileMetadata
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
            LocalFileMetadata("../bad\u0000:name?.pdf", "Text/Plain\r\nX-Evil: yes", -8L)
        )

        assertFalse(normalized.fileName.any { it.isISOControl() || it in "\\/:*?\"<>|" })
        assertEquals("application/octet-stream", normalized.mimeType)
        assertEquals(0L, normalized.sizeBytes)
    }

    @Test
    fun `voice ignores declared name and mime entirely`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.VOICE,
            LocalFileMetadata("clip.wav", "audio/wav", 100L)
        )

        assertEquals("voice.m4a", normalized.fileName)
        assertEquals("audio/mp4", normalized.mimeType)
        assertEquals(100L, normalized.sizeBytes)
    }

    @Test
    fun `typed image forces canonical extension and zeroes negative size`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.IMAGE,
            LocalFileMetadata("scan.tiff", "image/tiff", -5L)
        )

        assertEquals("scan.jpg", normalized.fileName)
        assertEquals("image/jpeg", normalized.mimeType)
        assertEquals(0L, normalized.sizeBytes)
    }

    @Test
    fun `else branch keeps sanitized name and valid declared mime`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.TEXT,
            LocalFileMetadata("dir/file.txt", "text/plain", 7L)
        )

        assertEquals("dir_file.txt", normalized.fileName)
        assertEquals("text/plain", normalized.mimeType)
        assertEquals(7L, normalized.sizeBytes)
    }

    @Test
    fun `dots only name falls back to file`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.FILE,
            LocalFileMetadata("...", "application/pdf", 5L)
        )

        assertEquals("file", normalized.fileName)
        assertEquals("application/pdf", normalized.mimeType)
    }

    @Test
    fun `long name is truncated to 120 chars`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.FILE,
            LocalFileMetadata("a".repeat(200) + ".pdf", "application/pdf", 9L)
        )

        assertEquals(120, normalized.fileName.length)
    }

    private fun assertMetadata(
        type: MessageType,
        name: String,
        mime: String,
        expectedName: String,
        expectedMime: String
    ) {
        val normalized = normalizeAttachmentMetadata(type, LocalFileMetadata(name, mime, 12L))
        assertEquals(expectedName, normalized.fileName)
        assertEquals(expectedMime, normalized.mimeType)
        assertEquals(12L, normalized.sizeBytes)
    }
}
