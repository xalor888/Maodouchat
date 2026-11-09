package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class AttachmentImagePreparePolicyTest {

    @Test
    fun `extension is replaced with jpg`() {
        val metadata = AttachmentImagePreparePolicy.compressedImageMetadata("photo.png", 12_345L)
        assertEquals("photo.jpg", metadata.fileName)
        assertEquals("image/jpeg", metadata.mimeType)
        assertEquals(12_345L, metadata.sizeBytes)
    }

    @Test
    fun `name without extension gains jpg suffix`() {
        val metadata = AttachmentImagePreparePolicy.compressedImageMetadata("photo", 100L)
        assertEquals("photo.jpg", metadata.fileName)
    }

    @Test
    fun `multi-dot name keeps inner dots`() {
        val metadata = AttachmentImagePreparePolicy.compressedImageMetadata("archive.tar.png", 100L)
        assertEquals("archive.tar.jpg", metadata.fileName)
    }

    @Test
    fun `blank stem falls back to image`() {
        val metadata = AttachmentImagePreparePolicy.compressedImageMetadata("", 100L)
        assertEquals("image.jpg", metadata.fileName)
    }

    @Test
    fun `dot-only name falls back to image`() {
        val metadata = AttachmentImagePreparePolicy.compressedImageMetadata(".png", 100L)
        assertEquals("image.jpg", metadata.fileName)
    }

    @Test
    fun `stem longer than limit is truncated to 116 chars`() {
        val longName = "a".repeat(200) + ".png"
        val metadata = AttachmentImagePreparePolicy.compressedImageMetadata(longName, 100L)
        assertEquals("a".repeat(116) + ".jpg", metadata.fileName)
    }

    @Test
    fun `stem at the limit is kept unchanged`() {
        val name = "b".repeat(116) + ".png"
        val metadata = AttachmentImagePreparePolicy.compressedImageMetadata(name, 100L)
        assertEquals("b".repeat(116) + ".jpg", metadata.fileName)
    }
}
