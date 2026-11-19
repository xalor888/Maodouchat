package com.maodouchat.domain.messaging

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import com.maodouchat.core.model.MessageType

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
