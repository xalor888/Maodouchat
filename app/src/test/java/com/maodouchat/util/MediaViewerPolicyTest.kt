package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaViewerPolicyTest {
    @Test
    fun `mime helpers`() {
        assertTrue(MediaViewerPolicy.isImageMime("image/jpeg"))
        assertTrue(MediaViewerPolicy.isImageMime("image/gif"))
        assertFalse(MediaViewerPolicy.isImageMime("video/mp4"))
        assertTrue(MediaViewerPolicy.isVideoMime("video/mp4"))
        assertEquals("image/jpeg", MediaViewerPolicy.defaultMime("IMAGE", null))
        assertEquals("image/png", MediaViewerPolicy.defaultMime("IMAGE", "image/png"))
    }

    @Test
    fun `file name defaults`() {
        assertEquals("photo.png", MediaViewerPolicy.defaultFileName("IMAGE", "photo.png", "image/png"))
        val generated = MediaViewerPolicy.defaultFileName("GIF", null, "image/gif")
        assertTrue(generated.startsWith("gif_"))
        assertTrue(generated.endsWith(".gif"))
    }

    @Test
    fun `scale clamp and double tap`() {
        assertEquals(1f, MediaViewerPolicy.clampScale(0.2f))
        assertEquals(8f, MediaViewerPolicy.clampScale(9f))
        assertEquals(MediaViewerPolicy.DOUBLE_TAP_SCALE, MediaViewerPolicy.nextDoubleTapScale(1f))
        assertEquals(MediaViewerPolicy.MIN_SCALE, MediaViewerPolicy.nextDoubleTapScale(2.5f))
    }

    @Test
    fun `export requires local readable`() {
        assertTrue(MediaViewerPolicy.canExportLocal(true))
        assertFalse(MediaViewerPolicy.canExportLocal(false))
    }
}
