package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmallPureModelsTest {

    @Test
    fun normalizeAttachmentMetadataImageToJpg() {
        val out = normalizeAttachmentMetadata(
            MessageType.IMAGE,
            LocalFileMetadata("pic.PNG", "image/png", 100L)
        )
        assertEquals("pic.jpg", out.fileName)
        assertEquals("image/jpeg", out.mimeType)
        assertEquals(100L, out.sizeBytes)
    }

    @Test
    fun normalizeVideoMetadataDeclaredMimeWins() {
        val out = normalizeVideoMetadata("clip.mov", "video/quicktime", 2048L)
        assertEquals("clip.mov", out.fileName)
        assertEquals("video/quicktime", out.mimeType)
        assertEquals(2048L, out.sizeBytes)
    }
}
