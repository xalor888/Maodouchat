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
    fun `mime helpers are case insensitive and null safe`() {
        assertFalse(MediaViewerPolicy.isImageMime(null))
        assertFalse(MediaViewerPolicy.isVideoMime(null))
        assertTrue(MediaViewerPolicy.isImageMime("IMAGE/JPEG"))
        assertTrue(MediaViewerPolicy.isVideoMime("VIDEO/MP4"))
        assertTrue(MediaViewerPolicy.isImageMime("application/x-gif"))
        assertTrue(MediaViewerPolicy.isImageMime("image"))
        assertFalse(MediaViewerPolicy.isImageMime("text/plain"))
        assertFalse(MediaViewerPolicy.isVideoMime("image/jpeg"))
    }

    @Test
    fun `defaultMime falls back on blank explicit mime`() {
        assertEquals("image/jpeg", MediaViewerPolicy.defaultMime("IMAGE", "   "))
        assertEquals("video/mp4", MediaViewerPolicy.defaultMime("video", null))
        assertEquals("application/octet-stream", MediaViewerPolicy.defaultMime("STICKER", null))
        assertEquals("application/octet-stream", MediaViewerPolicy.defaultMime(null, null))
    }

    @Test
    fun `defaultFileName honors explicit trimmed name`() {
        assertEquals(
            "photo.png",
            MediaViewerPolicy.defaultFileName("IMAGE", "  photo.png  ", "image/png")
        )
        val video = MediaViewerPolicy.defaultFileName("VIDEO", " ", "video/mp4")
        assertTrue(video.startsWith("video_"))
        assertTrue(video.endsWith(".mp4"))
        val webp = MediaViewerPolicy.defaultFileName("IMAGE", null, "image/webp")
        assertTrue(webp.endsWith(".webp"))
        val unknown = MediaViewerPolicy.defaultFileName("FILE", null, "text/plain")
        assertTrue(unknown.startsWith("media_"))
        assertTrue(unknown.endsWith(".bin"))
    }

    @Test
    fun `double tap toggles and NaN scale falls back to min`() {
        // 边界：恰好 1.05f 不算"已放大"，1.06f 才回落
        assertEquals(3.5f, MediaViewerPolicy.nextDoubleTapScale(1.05f))
        assertEquals(1f, MediaViewerPolicy.nextDoubleTapScale(1.06f))
        // 重复双击在 1x ↔ 3.5x 间稳定来回
        var s = 1f
        repeat(4) { s = MediaViewerPolicy.nextDoubleTapScale(s) }
        assertEquals(1f, s)
        // 回归（9.162）：NaN 缩放回落最小比例，不透传
        assertEquals(1f, MediaViewerPolicy.clampScale(Float.NaN))
        assertEquals(8f, MediaViewerPolicy.clampScale(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `export and share share the same gate`() {
        assertFalse(MediaViewerPolicy.canExportLocal(false))
        assertFalse(MediaViewerPolicy.canShareLocal(false))
        assertFalse(MediaViewerPolicy.canExportLocal(true, secretChat = true))
        assertFalse(MediaViewerPolicy.canShareLocal(true, secretChat = true))
        // exportBlockEnabled 是预留位，目前不影响判定——两处保持一致
        assertTrue(MediaViewerPolicy.canExportLocal(true, exportBlockEnabled = false))
        assertTrue(MediaViewerPolicy.canShareLocal(true, exportBlockEnabled = false))
    }

    @Test
    fun `export requires local readable`() {
        assertTrue(MediaViewerPolicy.canExportLocal(true))
        assertFalse(MediaViewerPolicy.canExportLocal(false))
        assertFalse(MediaViewerPolicy.canExportLocal(true, secretChat = true, exportBlockEnabled = false))
        assertFalse(MediaViewerPolicy.canShareLocal(true, secretChat = true, exportBlockEnabled = false))
    }
}
