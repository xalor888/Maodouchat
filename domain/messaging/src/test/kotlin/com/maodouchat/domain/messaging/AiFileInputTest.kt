package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageMeta
import com.maodouchat.core.model.MessageType
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiFileInputTest {

    private fun b64(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun message(
        content: String = "file:///sdcard/report.pdf",
        fileName: String? = null,
    ) = Message(
        id = "m1",
        chatId = "c1",
        senderId = "u1",
        content = content,
        type = MessageType.FILE,
        meta = MessageMeta(fileName = fileName),
    )

    @Test
    fun pdfExtensionMapsToPdfMime() {
        val input = b64("x")
        val result = resolveAiFileInput(message(fileName = "report.PDF"), input)
        assertEquals("application/pdf", result?.mimeType, "PDF 扩展名")
        assertEquals("report.PDF", result?.fileName, "原文件名保留")
        assertEquals(input, result?.base64, "base64 原样回传")
    }

    @Test
    fun knownExtensionsMapToCanonicalMime() {
        val cases = mapOf(
            "a.txt" to "text/plain",
            "a.md" to "text/markdown",
            "a.markdown" to "text/markdown",
            "a.csv" to "text/csv",
            "a.json" to "application/json",
            "a.xml" to "application/xml",
        )
        for ((name, mime) in cases) {
            assertEquals(mime, resolveAiFileInput(message(fileName = name), b64("x"))?.mimeType, name)
        }
    }

    @Test
    fun cachedNameComesFromContentUri() {
        val result = resolveAiFileInput(message(content = "file:///sdcard/report.pdf"), b64("x"))
        assertEquals("report.pdf", result?.fileName, "无 storedName 时取内容 URI 末段")
        assertEquals("application/pdf", result?.mimeType)
    }

    @Test
    fun storedNameBeatsCachedName() {
        val result = resolveAiFileInput(
            message(content = "file:///sdcard/other.pdf", fileName = "stored.txt"), b64("x"),
        )
        assertEquals("stored.txt", result?.fileName, "storedName 优先")
        assertEquals("text/plain", result?.mimeType)
    }

    @Test
    fun blankStoredNameFallsBackToCachedName() {
        val result = resolveAiFileInput(
            message(content = "file:///sdcard/note.txt", fileName = "  "), b64("x"),
        )
        assertEquals("note.txt", result?.fileName, "空白 storedName 视为缺失")
    }

    @Test
    fun candidateNameTruncatedTo120() {
        val result = resolveAiFileInput(message(fileName = "a".repeat(130) + ".pdf"), b64("x"))
        assertEquals(120, result?.fileName?.length, "候选名截断到 120 字")
    }

    @Test
    fun unknownExtensionWithPdfMagicSniffsPdf() {
        val result = resolveAiFileInput(message(fileName = "data.bin"), b64("%PDF-1.4 rest"))
        assertEquals("document.pdf", result?.fileName)
        assertEquals("application/pdf", result?.mimeType)
    }

    @Test
    fun unknownExtensionWithPlainTextSniffsTxt() {
        val result = resolveAiFileInput(message(fileName = "data.bin"), b64("hello world"))
        assertEquals("document.txt", result?.fileName)
        assertEquals("text/plain", result?.mimeType)
    }

    @Test
    fun blankTextReturnsNull() {
        assertNull(resolveAiFileInput(message(fileName = "data.bin"), b64("   ")), "空白文本拒绝")
    }

    @Test
    fun oversizedTextReturnsNull() {
        assertNull(resolveAiFileInput(message(fileName = "data.bin"), b64("x".repeat(120_001))), "超 120k 文本拒绝")
    }

    @Test
    fun nulByteReturnsNull() {
        assertNull(resolveAiFileInput(message(fileName = "data.bin"), b64("a\u0000b")), "含 NUL 拒绝")
    }

    @Test
    fun replacementCharReturnsNull() {
        assertNull(resolveAiFileInput(message(fileName = "data.bin"), b64("a\uFFFDb")), "含 U+FFFD 拒绝")
    }

    @Test
    fun invalidBase64ReturnsNull() {
        assertNull(resolveAiFileInput(message(fileName = "data.bin"), "!!!not-base64!!!"), "非法 base64 拒绝")
    }
}
