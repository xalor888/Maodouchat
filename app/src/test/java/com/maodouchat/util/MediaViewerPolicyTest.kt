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

    @Test
    fun `clamp pins exact bounds and infinities`() {
        assertEquals(1f, MediaViewerPolicy.clampScale(1f))
        assertEquals(8f, MediaViewerPolicy.clampScale(8f))
        assertEquals(1f, MediaViewerPolicy.clampScale(-5f))
        assertEquals(1f, MediaViewerPolicy.clampScale(Float.NEGATIVE_INFINITY))
        assertEquals(8f, MediaViewerPolicy.clampScale(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `double tap pins zero max and NaN`() {
        // NaN > 1.05f is false, so it falls through to the zoom-in branch
        assertEquals(3.5f, MediaViewerPolicy.nextDoubleTapScale(0f))
        assertEquals(1f, MediaViewerPolicy.nextDoubleTapScale(8f))
        assertEquals(3.5f, MediaViewerPolicy.nextDoubleTapScale(Float.NaN))
    }

    @Test
    fun `mime edge values`() {
        assertTrue(MediaViewerPolicy.isImageMime("gif"))
        assertFalse(MediaViewerPolicy.isImageMime(""))
        assertFalse(MediaViewerPolicy.isVideoMime("video"))
        assertTrue(MediaViewerPolicy.isVideoMime("VIDEO/"))
        assertTrue(MediaViewerPolicy.isImageMime("image/svg+xml"))
    }

    @Test
    fun `defaultMime keeps explicit mime verbatim and matches type case-insensitively`() {
        assertEquals(" image/png ", MediaViewerPolicy.defaultMime("IMAGE", " image/png "))
        assertEquals("image/gif", MediaViewerPolicy.defaultMime("Gif", null))
        assertEquals("application/octet-stream", MediaViewerPolicy.defaultMime("AUDIO", null))
    }

    @Test
    fun `defaultFileName prefix and extension order`() {
        val imageAsJpg = MediaViewerPolicy.defaultFileName("AUDIO", null, "image/jpeg")
        assertTrue(imageAsJpg.startsWith("media_"))
        assertTrue(imageAsJpg.endsWith(".jpg"))
        val gifTypeVideoMime = MediaViewerPolicy.defaultFileName("GIF", null, "video/mp4")
        assertTrue(gifTypeVideoMime.startsWith("gif_"))
        assertTrue(gifTypeVideoMime.endsWith(".mp4"))
        assertEquals(
            "my photo.png",
            MediaViewerPolicy.defaultFileName("IMAGE", "  my photo.png  ", "image/png")
        )
    }

    @Test
    fun `scale constants pinned and mid scale passes through`() {
        assertEquals(1f, MediaViewerPolicy.MIN_SCALE)
        assertEquals(8f, MediaViewerPolicy.MAX_SCALE)
        assertEquals(3.5f, MediaViewerPolicy.DOUBLE_TAP_SCALE)
        assertEquals(4.25f, MediaViewerPolicy.clampScale(4.25f))
        assertEquals(1f, MediaViewerPolicy.nextDoubleTapScale(3.5f))
    }

    @Test
    fun `mime family coverage`() {
        assertTrue(MediaViewerPolicy.isImageMime("image/webp"))
        assertTrue(MediaViewerPolicy.isVideoMime("video/webm"))
        assertFalse(MediaViewerPolicy.isImageMime("audio/mp3"))
        assertFalse(MediaViewerPolicy.isVideoMime("audio/mp3"))
        assertFalse(MediaViewerPolicy.isImageMime("application/pdf"))
        assertFalse(MediaViewerPolicy.isVideoMime("application/pdf"))
    }

    @Test
    fun `defaultMime explicit wins and type fallback covers gif and file`() {
        assertEquals("application/pdf", MediaViewerPolicy.defaultMime("IMAGE", "application/pdf"))
        assertEquals("image/gif", MediaViewerPolicy.defaultMime("gif", null))
        assertEquals("application/octet-stream", MediaViewerPolicy.defaultMime("file", null))
    }

    @Test
    fun `defaultFileName generated names follow mime before type`() {
        val videoTypeGifMime = MediaViewerPolicy.defaultFileName("VIDEO", null, "image/gif")
        assertTrue(videoTypeGifMime.startsWith("video_"))
        assertTrue(videoTypeGifMime.endsWith(".gif"))
        val noTypeVideoMime = MediaViewerPolicy.defaultFileName(null, null, "video/mp4")
        assertTrue(noTypeVideoMime.startsWith("media_"))
        assertTrue(noTypeVideoMime.endsWith(".mp4"))
        val emptyExplicitName = MediaViewerPolicy.defaultFileName("IMAGE", "", "image/png")
        assertTrue(emptyExplicitName.startsWith("image_"))
        assertTrue(emptyExplicitName.endsWith(".png"))
        val noTypeNoMime = MediaViewerPolicy.defaultFileName(null, null, null)
        assertTrue(noTypeNoMime.startsWith("media_"))
        assertTrue(noTypeNoMime.endsWith(".bin"))
    }

    @Test
    fun `export and share gates agree on the full truth table`() {
        listOf(
            Triple(true, false, true),
            Triple(false, false, false),
            Triple(true, true, false),
            Triple(false, true, false),
        ).forEach { (readable, secret, expected) ->
            assertEquals(expected, MediaViewerPolicy.canExportLocal(readable, secretChat = secret))
            assertEquals(expected, MediaViewerPolicy.canShareLocal(readable, secretChat = secret))
        }
    }

    @Test
    fun `image mime gif suffix and video prefix edges`() {
        assertTrue(MediaViewerPolicy.isImageMime("IMAGE/GIF"))
        assertTrue(MediaViewerPolicy.isImageMime("application/gif"))
        assertTrue(MediaViewerPolicy.isImageMime("image2/gif"))
        assertTrue(MediaViewerPolicy.isVideoMime("video/mp4;codecs=avc1"))
        assertFalse(MediaViewerPolicy.isVideoMime("videox/mp4"))
    }

    @Test
    fun `defaultMime spaced type names fall through and explicit wins`() {
        assertEquals("application/octet-stream", MediaViewerPolicy.defaultMime(" image ", null))
        assertEquals("application/octet-stream", MediaViewerPolicy.defaultMime("", null))
        assertEquals("video/webm", MediaViewerPolicy.defaultMime("VIDEO", "video/webm"))
        assertEquals("image/png", MediaViewerPolicy.defaultMime("GIF", "image/png"))
    }

    @Test
    fun `defaultFileName svg falls back to jpg and png beats video prefix`() {
        val svg = MediaViewerPolicy.defaultFileName("IMAGE", null, "image/svg+xml")
        assertTrue(svg.startsWith("image_"))
        assertTrue(svg.endsWith(".jpg"))
        val videoPng = MediaViewerPolicy.defaultFileName("VIDEO", null, "image/png")
        assertTrue(videoPng.startsWith("video_"))
        assertTrue(videoPng.endsWith(".png"))
        val gifWebp = MediaViewerPolicy.defaultFileName("GIF", null, "image/webp")
        assertTrue(gifWebp.startsWith("gif_"))
        assertTrue(gifWebp.endsWith(".webp"))
    }

    @Test
    fun `clampScale in-range passes through and double tap handles negatives`() {
        assertEquals(2.5f, MediaViewerPolicy.clampScale(2.5f))
        assertEquals(7.999f, MediaViewerPolicy.clampScale(7.999f))
        assertEquals(3.5f, MediaViewerPolicy.nextDoubleTapScale(-1f))
        assertEquals(3.5f, MediaViewerPolicy.nextDoubleTapScale(1.04f))
        assertEquals(1f, MediaViewerPolicy.nextDoubleTapScale(1.1f))
    }

    @Test
    fun `export and share gates ignore exportBlockEnabled in both directions`() {
        listOf(true, false).forEach { block ->
            assertTrue(MediaViewerPolicy.canExportLocal(true, exportBlockEnabled = block))
            assertTrue(MediaViewerPolicy.canShareLocal(true, exportBlockEnabled = block))
            assertFalse(MediaViewerPolicy.canExportLocal(false, exportBlockEnabled = block))
            assertFalse(MediaViewerPolicy.canShareLocal(false, exportBlockEnabled = block))
        }
        assertTrue(MediaViewerPolicy.canExportLocal(true, false, false))
        assertTrue(MediaViewerPolicy.canShareLocal(true, false, false))
    }
}
