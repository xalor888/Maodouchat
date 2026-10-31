package com.maodouchat.chatdetail

import com.maodouchat.data.model.MessageType
import com.maodouchat.attachment.normalizeAttachmentMetadata
import com.maodouchat.util.LocalFileMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `video falls back to existing extension when declared mime is unknown`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.VIDEO,
            LocalFileMetadata("clip.mkv", "application/octet-stream", 12L)
        )

        assertEquals("clip.mkv", normalized.fileName)
        assertEquals("video/x-matroska", normalized.mimeType)
        assertEquals(12L, normalized.sizeBytes)
    }

    @Test
    fun `video with unknown extension and mime falls back to mp4`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.VIDEO,
            LocalFileMetadata("clip.xyz", "application/octet-stream", 12L)
        )

        assertEquals("clip.mp4", normalized.fileName)
        assertEquals("video/mp4", normalized.mimeType)
    }

    @Test
    fun `declared video mime is lowercased before lookup`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.VIDEO,
            LocalFileMetadata("clip.mov", "VIDEO/QUICKTIME", 12L)
        )

        assertEquals("clip.mov", normalized.fileName)
        assertEquals("video/quicktime", normalized.mimeType)
    }

    @Test
    fun `gif forces canonical extension and mime`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.GIF,
            LocalFileMetadata("dance.png", "image/png", 12L)
        )

        assertEquals("dance.gif", normalized.fileName)
        assertEquals("image/gif", normalized.mimeType)
    }

    @Test
    fun `blank filename falls back to file with canonical extension`() {
        val image = normalizeAttachmentMetadata(
            MessageType.IMAGE,
            LocalFileMetadata("", "image/png", 3L)
        )
        assertEquals("file.jpg", image.fileName)
        assertEquals("image/jpeg", image.mimeType)

        val file = normalizeAttachmentMetadata(
            MessageType.FILE,
            LocalFileMetadata("   ", "application/pdf", 3L)
        )
        assertEquals("file", file.fileName)
    }

    @Test
    fun `video with no extension gains the declared extension`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.VIDEO,
            LocalFileMetadata("clip", "video/webm", 4L)
        )

        assertEquals("clip.webm", normalized.fileName)
        assertEquals("video/webm", normalized.mimeType)
        assertEquals(4L, normalized.sizeBytes)
    }

    @Test
    fun `video extension lookup is case insensitive`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.VIDEO,
            LocalFileMetadata("CLIP.MKV", "application/octet-stream", 4L)
        )

        assertEquals("CLIP.mkv", normalized.fileName)
        assertEquals("video/x-matroska", normalized.mimeType)
        assertEquals(4L, normalized.sizeBytes)
    }

    @Test
    fun `gif long name keeps 120 char cap with canonical extension`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.GIF,
            LocalFileMetadata("a".repeat(200) + ".png", "image/png", 1L)
        )

        assertEquals(120, normalized.fileName.length)
        assertTrue(normalized.fileName.endsWith(".gif"))
        assertEquals("image/gif", normalized.mimeType)
    }

    @Test
    fun `voice zeroes negative size`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.VOICE,
            LocalFileMetadata("clip.wav", "audio/wav", -3L)
        )

        assertEquals("voice.m4a", normalized.fileName)
        assertEquals("audio/mp4", normalized.mimeType)
        assertEquals(0L, normalized.sizeBytes)
    }

    @Test
    fun `dots and spaces only name falls back to file`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.FILE,
            LocalFileMetadata("  ..  ", "application/pdf", 2L)
        )

        assertEquals("file", normalized.fileName)
        assertEquals("application/pdf", normalized.mimeType)
        assertEquals(2L, normalized.sizeBytes)
    }

    @Test
    fun `image name without extension gains canonical extension`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.IMAGE,
            LocalFileMetadata("photo", "image/png", 6L)
        )

        assertEquals("photo.jpg", normalized.fileName)
        assertEquals("image/jpeg", normalized.mimeType)
        assertEquals(6L, normalized.sizeBytes)
    }

    @Test
    fun `image uppercase base keeps case while forcing jpg`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.IMAGE,
            LocalFileMetadata("PHOTO.PNG", "image/png", 6L)
        )

        assertEquals("PHOTO.jpg", normalized.fileName)
        assertEquals("image/jpeg", normalized.mimeType)
    }

    @Test
    fun `file mime is trimmed and lowercased`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.FILE,
            LocalFileMetadata("doc.txt", "  Application/PDF  ", 8L)
        )

        assertEquals("doc.txt", normalized.fileName)
        assertEquals("application/pdf", normalized.mimeType)
        assertEquals(8L, normalized.sizeBytes)
    }

    @Test
    fun `video 3gp extension maps both ways`() {
        val fromExtension = normalizeAttachmentMetadata(
            MessageType.VIDEO,
            LocalFileMetadata("clip.3gp", "application/octet-stream", 10L)
        )
        assertEquals("clip.3gp", fromExtension.fileName)
        assertEquals("video/3gpp", fromExtension.mimeType)

        val fromMime = normalizeAttachmentMetadata(
            MessageType.VIDEO,
            LocalFileMetadata("clip.avi", "video/3gpp", 10L)
        )
        assertEquals("clip.3gp", fromMime.fileName)
        assertEquals("video/3gpp", fromMime.mimeType)
    }

    @Test
    fun `video multi dot base is preserved`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.VIDEO,
            LocalFileMetadata("my.clip.mp4", "application/octet-stream", 11L)
        )

        assertEquals("my.clip.mp4", normalized.fileName)
        assertEquals("video/mp4", normalized.mimeType)
        assertEquals(11L, normalized.sizeBytes)
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

    @Test
    fun `video mkv extension maps to matroska mime`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.VIDEO,
            LocalFileMetadata("clip.mkv", "application/octet-stream", 10L)
        )

        assertEquals("clip.mkv", normalized.fileName)
        assertEquals("video/x-matroska", normalized.mimeType)
        assertEquals(10L, normalized.sizeBytes)
    }

    @Test
    fun `sticker falls through to else branch with sanitized name`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.STICKER,
            LocalFileMetadata("my sticker.png", "image/png", 7L)
        )

        assertEquals("my sticker.png", normalized.fileName)
        assertEquals("image/png", normalized.mimeType)
        assertEquals(7L, normalized.sizeBytes)
    }

    @Test
    fun `overlong file mime is truncated to 100 chars and kept`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.FILE,
            LocalFileMetadata("doc.txt", "application/" + "x".repeat(100), 8L)
        )

        assertEquals(100, normalized.mimeType.length)
        assertTrue(normalized.mimeType.startsWith("application/"))
    }

    @Test
    fun `image long base is capped so total stays 120`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.IMAGE,
            LocalFileMetadata("a".repeat(200) + ".png", "image/png", 9L)
        )

        assertEquals(120, normalized.fileName.length)
        assertTrue(normalized.fileName.endsWith(".jpg"))
    }

    @Test
    fun `gif uppercase base keeps case while forcing gif extension`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.GIF,
            LocalFileMetadata("DANCE.PNG", "image/png", 12L)
        )

        assertEquals("DANCE.gif", normalized.fileName)
        assertEquals("image/gif", normalized.mimeType)
    }

    @Test
    fun `image dots and spaces only name falls back to file jpg`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.IMAGE,
            LocalFileMetadata(" . . ", "image/png", 6L)
        )

        assertEquals("file.jpg", normalized.fileName)
        assertEquals("image/jpeg", normalized.mimeType)
        assertEquals(6L, normalized.sizeBytes)
    }

    @Test
    fun `video declared mime with whitespace and uppercase maps over unknown extension`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.VIDEO,
            LocalFileMetadata("clip.avi", "  VIDEO/WEBM ", 4L)
        )

        assertEquals("clip.webm", normalized.fileName)
        assertEquals("video/webm", normalized.mimeType)
        assertEquals(4L, normalized.sizeBytes)
    }

    @Test
    fun `video trailing dot name gains mp4`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.VIDEO,
            LocalFileMetadata("clip.", "application/octet-stream", 5L)
        )

        assertEquals("clip.mp4", normalized.fileName)
        assertEquals("video/mp4", normalized.mimeType)
        assertEquals(5L, normalized.sizeBytes)
    }

    @Test
    fun `file mime without slash or with two slashes fails closed`() {
        val noSlash = normalizeAttachmentMetadata(
            MessageType.FILE,
            LocalFileMetadata("doc.txt", "textplain", 8L)
        )
        assertEquals("application/octet-stream", noSlash.mimeType)

        val twoSlashes = normalizeAttachmentMetadata(
            MessageType.FILE,
            LocalFileMetadata("doc.txt", "a/b/c", 8L)
        )
        assertEquals("application/octet-stream", twoSlashes.mimeType)
    }

    @Test
    fun `sticker blank name falls back to file keeping declared mime`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.STICKER,
            LocalFileMetadata("   ", "image/webp", 7L)
        )

        assertEquals("file", normalized.fileName)
        assertEquals("image/webp", normalized.mimeType)
        assertEquals(7L, normalized.sizeBytes)
    }

    @Test
    fun `gif unsafe chars are sanitized before forcing extension`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.GIF,
            LocalFileMetadata("my:pic?.png", "image/png", 5L)
        )

        assertEquals("my_pic_.gif", normalized.fileName)
        assertEquals("image/gif", normalized.mimeType)
        assertEquals(5L, normalized.sizeBytes)
    }

    @Test
    fun `video valid but unmapped mime falls back to existing mov extension`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.VIDEO,
            LocalFileMetadata("clip.mov", "video/unknown", 4L)
        )

        assertEquals("clip.mov", normalized.fileName)
        assertEquals("video/quicktime", normalized.mimeType)
        assertEquals(4L, normalized.sizeBytes)
    }

    @Test
    fun `file blank mime fails closed keeping name and size`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.FILE,
            LocalFileMetadata("doc.txt", "", 8L)
        )

        assertEquals("doc.txt", normalized.fileName)
        assertEquals("application/octet-stream", normalized.mimeType)
        assertEquals(8L, normalized.sizeBytes)
    }

    @Test
    fun `file vendor mime with dots and dashes is kept verbatim`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.FILE,
            LocalFileMetadata(
                "doc.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                8L
            )
        )

        assertEquals("doc.xlsx", normalized.fileName)
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            normalized.mimeType
        )
        assertEquals(8L, normalized.sizeBytes)
    }

    @Test
    fun `video declared mp4 wins over unknown extension`() {
        val normalized = normalizeAttachmentMetadata(
            MessageType.VIDEO,
            LocalFileMetadata("clip.xyz", "video/mp4", 6L)
        )

        assertEquals("clip.mp4", normalized.fileName)
        assertEquals("video/mp4", normalized.mimeType)
        assertEquals(6L, normalized.sizeBytes)
    }
}
