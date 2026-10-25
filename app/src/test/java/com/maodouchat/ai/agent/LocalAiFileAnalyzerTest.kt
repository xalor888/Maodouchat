package com.maodouchat.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LocalAiFileAnalyzerTest {

    @Test
    fun `text files decode without a network call`() {
        val body = "会议纪要：周六发布"
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("meeting.txt", "text/plain", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertTrue(prepared.text.contains("周六发布"))
    }

    @Test
    fun `binary junk is rejected`() {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(0, 1, 2, 3, 0))
        assertNull(LocalAiFileAnalyzer.prepare("blob.bin", "application/octet-stream", encoded))
    }

    @Test
    fun `invalid pdf bytes are rejected`() {
        val encoded = Base64.getEncoder().encodeToString("%PDF-not-a-real-file".toByteArray())
        assertNull(LocalAiFileAnalyzer.prepare("fake.pdf", "application/pdf", encoded))
    }

    @Test
    fun `long text is truncated to MAX_TEXT_CHARS`() {
        val body = "x".repeat(LocalAiFileAnalyzer.MAX_TEXT_CHARS + 5)
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("long.txt", "text/plain", encoded)
        assertNotNull(prepared)
        assertEquals(LocalAiFileAnalyzer.MAX_TEXT_CHARS, prepared!!.text.length)
        assertTrue(prepared.text.all { it == 'x' })
    }

    @Test
    fun `whitespace-only text is rejected`() {
        val encoded = Base64.getEncoder().encodeToString("  \n\t ".toByteArray(Charsets.UTF_8))
        assertNull(LocalAiFileAnalyzer.prepare("blank.txt", "text/plain", encoded))
    }

    @Test
    fun `text containing null byte is rejected`() {
        val encoded = Base64.getEncoder().encodeToString("ab\u0000cd".toByteArray(Charsets.UTF_8))
        assertNull(LocalAiFileAnalyzer.prepare("nul.txt", "text/plain", encoded))
    }

    @Test
    fun `json mime is accepted as text`() {
        val encoded = Base64.getEncoder().encodeToString("{\"a\":1}".toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("data.json", "application/json", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals("{\"a\":1}", prepared.text)
    }

    @Test
    fun `url-safe base64 is normalized before decode`() {
        // "???" encodes to "Pz8/" in standard base64; url-safe form swaps '/' for '_'.
        val prepared = LocalAiFileAnalyzer.prepare("q.txt", "text/plain", "Pz8_")
        assertNotNull(prepared)
        assertEquals("???", prepared!!.text)
    }

    @Test
    fun `uppercase mime is lowercased before the text check`() {
        val encoded = Base64.getEncoder().encodeToString("hi".toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("a.txt", "TEXT/PLAIN", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals("hi", prepared.text)
    }

    @Test
    fun `blank file name defaults to document txt`() {
        val encoded = Base64.getEncoder().encodeToString("hi".toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("", "text/plain", encoded)
        assertNotNull(prepared)
        assertEquals("document.txt", prepared!!.fileName)
        assertEquals("text/plain", prepared.mimeType)
    }

    @Test
    fun `illegal base64 is rejected`() {
        assertNull(LocalAiFileAnalyzer.prepare("a.txt", "text/plain", "!!!"))
    }

    @Test
    fun `empty input is rejected`() {
        assertNull(LocalAiFileAnalyzer.prepare("a.txt", "text/plain", ""))
    }

    @Test
    fun `xml mime is accepted as text`() {
        val encoded = Base64.getEncoder().encodeToString("<a/>".toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("a.xml", "application/xml", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals("<a/>", prepared.text)
    }

    @Test
    fun `pdf suffix forces pdf path and rejects text bytes`() {
        // 扩展名 .pdf 让 looksPdf 为真，文本字节走渲染路径失败，整体回 null。
        val encoded = Base64.getEncoder().encodeToString("hello".toByteArray(Charsets.UTF_8))
        assertNull(LocalAiFileAnalyzer.prepare("notes.pdf", "text/plain", encoded))
    }

    @Test
    fun `pdf magic bytes are sniffed regardless of mime`() {
        val encoded = Base64.getEncoder().encodeToString("%PDF-1.4 junk".toByteArray(Charsets.UTF_8))
        assertNull(LocalAiFileAnalyzer.prepare("x.bin", "application/octet-stream", encoded))
    }

    @Test
    fun `replacement character text is rejected`() {
        val encoded = Base64.getEncoder().encodeToString("ab\uFFFDcd".toByteArray(Charsets.UTF_8))
        assertNull(LocalAiFileAnalyzer.prepare("bad.txt", "text/plain", encoded))
    }

    @Test
    fun `markdown mime is accepted as text`() {
        val encoded = Base64.getEncoder().encodeToString("# hi".toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("a.md", "text/markdown", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals("# hi", prepared.text)
    }

    @Test
    fun `exactly max chars text is kept whole`() {
        val body = "x".repeat(LocalAiFileAnalyzer.MAX_TEXT_CHARS)
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("exact.txt", "text/plain", encoded)
        assertNotNull(prepared)
        assertEquals(LocalAiFileAnalyzer.MAX_TEXT_CHARS, prepared!!.text.length)
        assertEquals(body, prepared.text)
    }

    @Test
    fun `csv mime is accepted as text`() {
        val encoded = Base64.getEncoder().encodeToString("a,b".toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("a.csv", "text/csv", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals("a,b", prepared.text)
    }

    @Test
    fun `blank mime is rejected even for text bytes`() {
        val encoded = Base64.getEncoder().encodeToString("hi".toByteArray(Charsets.UTF_8))
        assertNull(LocalAiFileAnalyzer.prepare("a.txt", "", encoded))
    }

    @Test
    fun `uppercase pdf extension forces pdf path`() {
        val encoded = Base64.getEncoder().encodeToString("hello".toByteArray(Charsets.UTF_8))
        assertNull(LocalAiFileAnalyzer.prepare("notes.PDF", "text/plain", encoded))
    }

    @Test
    fun `url-safe dash is normalized before decode`() {
        // ">>>" encodes to "Pj4+" in standard base64; url-safe form swaps '+' for '-'.
        val prepared = LocalAiFileAnalyzer.prepare("q.txt", "text/plain", "Pj4-")
        assertNotNull(prepared)
        assertEquals(">>>", prepared!!.text)
    }

    @Test
    fun `pdf magic boundary at five bytes`() {
        val five = Base64.getEncoder().encodeToString("%PDF-".toByteArray(Charsets.US_ASCII))
        assertNull(LocalAiFileAnalyzer.prepare("x.txt", "text/plain", five))
        val four = Base64.getEncoder().encodeToString("%PDF".toByteArray(Charsets.US_ASCII))
        val prepared = LocalAiFileAnalyzer.prepare("x.txt", "text/plain", four)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals("%PDF", prepared.text)
    }
}
