package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaViewerPolicyTest {

    @Test
    fun `mime classification distinguishes image and video`() {
        assertTrue(MediaViewerPolicy.isImageMime("image/png"))
        assertTrue(MediaViewerPolicy.isImageMime("image"))
        assertFalse(MediaViewerPolicy.isImageMime("video/mp4"))
        assertTrue(MediaViewerPolicy.isVideoMime("video/mp4"))
        assertFalse(MediaViewerPolicy.isVideoMime("image/png"))
    }

    @Test
    fun `defaultMime falls back by type name`() {
        assertEquals("video/mp4", MediaViewerPolicy.defaultMime("VIDEO", null))
        assertEquals("image/gif", MediaViewerPolicy.defaultMime("IMAGE", "image/gif"))
        assertEquals("application/octet-stream", MediaViewerPolicy.defaultMime(null, null))
    }

    @Test
    fun `clampScale maps NaN to minimum and clamps bounds`() {
        assertEquals(MediaViewerPolicy.MIN_SCALE, MediaViewerPolicy.clampScale(Float.NaN), 0f)
        assertEquals(MediaViewerPolicy.MAX_SCALE, MediaViewerPolicy.clampScale(100f), 0f)
        assertEquals(MediaViewerPolicy.MIN_SCALE, MediaViewerPolicy.clampScale(0f), 0f)
    }

    @Test
    fun `double tap toggles between 1x and double tap scale`() {
        assertEquals(MediaViewerPolicy.DOUBLE_TAP_SCALE, MediaViewerPolicy.nextDoubleTapScale(1f), 0f)
        assertEquals(MediaViewerPolicy.MIN_SCALE, MediaViewerPolicy.nextDoubleTapScale(3.5f), 0f)
    }

    @Test
    fun `export and share require readable non-secret media`() {
        assertTrue(MediaViewerPolicy.canExportLocal(true))
        assertFalse(MediaViewerPolicy.canExportLocal(true, secretChat = true))
        assertFalse(MediaViewerPolicy.canShareLocal(false))
    }
}
