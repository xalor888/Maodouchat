package com.maodouchat.attachment

import com.maodouchat.util.MediaCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G155：`AttachmentPreparationModel.normalizeVideoMetadata` 的测试。
 *
 * 它决定落库视频的「扩展名 + mime」：**声明 mime 优先，文件名扩展名次之，最后兜底 mp4**。
 * 顺序错了会让 `video/quicktime` 的文件被存成 `.mp4`——某些播放器就打不开。
 */
class NormalizeVideoMetadataTest {

    private fun normalize(fileName: String, mime: String, size: Long = 1024L) =
        normalizeVideoMetadata(fileName, mime, size)

    /** 与实现同一张表，用于自洽校验（不复制判断，只验证 extension→mime 一致）。 */
    private val extToMime = mapOf(
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "3gp" to "video/3gpp",
        "mkv" to "video/x-matroska",
    )

    @Test
    fun `declared mime drives the extension`() {
        // 文件名扩展名与声明 mime 不一致时，声明赢
        val out = normalize("clip.avi", "video/quicktime")
        assertEquals("clip.mov", out.fileName)
        assertEquals("video/quicktime", out.mimeType)
    }

    @Test
    fun `declared mime wins even when the file name extension is also a known video`() {
        // 两侧都可识别且互相矛盾时，必须是声明 mime 赢——
        // 这条是唯一能区分「声明优先」与「文件名优先」两种回退顺序的用例。
        val out = normalize("clip.mkv", "video/quicktime")
        assertEquals("clip.mov", out.fileName)
        assertEquals("video/quicktime", out.mimeType)
        // 反方向再来一组
        val out2 = normalize("clip.mov", "video/webm")
        assertEquals("clip.webm", out2.fileName)
        assertEquals("video/webm", out2.mimeType)
    }

    @Test
    fun `every known declared mime picks its own extension`() {
        extToMime.forEach { (ext, mime) ->
            val out = normalize("v.unknown", mime)
            assertEquals("mime $mime 应对应 $ext", "v.$ext", out.fileName)
            assertEquals(mime, out.mimeType)
        }
    }

    @Test
    fun `unknown declared mime falls back to the file name extension`() {
        val out = normalize("clip.mkv", "application/octet-stream")
        assertEquals("clip.mkv", out.fileName)
        assertEquals("video/x-matroska", out.mimeType)
    }

    @Test
    fun `neither side recognizable falls back to mp4`() {
        val out = normalize("clip.avi", "application/octet-stream")
        assertEquals("clip.mp4", out.fileName)
        assertEquals("video/mp4", out.mimeType)
    }

    @Test
    fun `file name without an extension falls back to mp4`() {
        val out = normalize("clip", "application/octet-stream")
        assertEquals("clip.mp4", out.fileName)
    }

    @Test
    fun `declared mime is normalized for case and spaces`() {
        // normalizeMimeType 会 trim + lowercase
        val out = normalize("v.avi", "  VIDEO/QUICKTIME  ")
        assertEquals("v.mov", out.fileName)
        assertEquals("video/quicktime", out.mimeType)
    }

    @Test
    fun `file name extension is lowercased before lookup`() {
        val out = normalize("CLIP.MKV", "application/octet-stream")
        assertEquals("CLIP.mkv", out.fileName)
        assertEquals("video/x-matroska", out.mimeType)
    }

    @Test
    fun `a non video file name extension is not used`() {
        // .txt 不在视频扩展名表里，所以回退 mp4
        val out = normalize("notes.txt", "application/octet-stream")
        assertEquals("notes.mp4", out.fileName)
        assertEquals("video/mp4", out.mimeType)
    }

    @Test
    fun `extension and mime are always self consistent`() {
        listOf(
            Triple("a.mov", "video/quicktime", 1L),
            Triple("b", "video/webm", 2L),
            Triple("c.webm", "application/octet-stream", 3L),
            Triple("d.3gp", "video/3gpp", 4L),
            Triple("e", "video/x-matroska", 5L),
        ).forEach { (name, mime, size) ->
            val out = normalize(name, mime, size)
            val ext = out.fileName.substringAfterLast('.', "")
            assertEquals("$name 的扩展名没落在表里", true, extToMime.containsKey(ext))
            assertEquals("$name 的 mime 与扩展名不一致", extToMime.getValue(ext), out.mimeType)
            assertEquals(size, out.sizeBytes)
        }
    }

    @Test
    fun `size is carried through untouched`() {
        assertEquals(987654321L, normalize("v.mp4", "video/mp4", 987654321L).sizeBytes)
    }
}
