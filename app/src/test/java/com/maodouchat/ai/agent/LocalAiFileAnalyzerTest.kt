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

    @Test
    fun `text mime with parameters is accepted and stored lowercased`() {
        // text/ 前缀判定只看开头，带 charset 参数仍是文本；存入的 mime 是小写化后的原串。
        val encoded = Base64.getEncoder().encodeToString("hi".toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("a.txt", "TEXT/PLAIN; CHARSET=UTF-8", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals("text/plain; charset=utf-8", prepared.mimeType)
        assertEquals("hi", prepared.text)
    }

    @Test
    fun `json mime with parameters is rejected by exact match`() {
        // application/json 的文本判定是精确相等，带参数后缀不再命中，直接回 null。
        val encoded = Base64.getEncoder().encodeToString("{\"a\":1}".toByteArray(Charsets.UTF_8))
        assertNull(LocalAiFileAnalyzer.prepare("a.json", "application/json; charset=utf-8", encoded))
    }

    @Test
    fun `whitespace-only file name falls back to document txt`() {
        // ifBlank 把纯空白文件名也当空处理。
        val encoded = Base64.getEncoder().encodeToString("hi".toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("   ", "text/plain", encoded)
        assertNotNull(prepared)
        assertEquals("document.txt", prepared!!.fileName)
    }

    @Test
    fun `multibyte text truncates by chars not bytes`() {
        // take 按字符数截断：12005 个中文字符剩 12000 个，字节数远超 12000 也不影响。
        val body = "中".repeat(LocalAiFileAnalyzer.MAX_TEXT_CHARS + 5)
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("long.txt", "text/plain", encoded)
        assertNotNull(prepared)
        assertEquals(LocalAiFileAnalyzer.MAX_TEXT_CHARS, prepared!!.text.length)
        assertTrue(prepared.text.all { it == '中' })
    }

    @Test
    fun `leading mime whitespace is rejected while trailing still matches text prefix`() {
        // 前导空白让 startsWith("text/") 失效；尾随空白不影响前缀判定，照常接受。
        val encoded = Base64.getEncoder().encodeToString("hi".toByteArray(Charsets.UTF_8))
        assertNull(LocalAiFileAnalyzer.prepare("a.txt", " text/plain", encoded))
        val trailing = LocalAiFileAnalyzer.prepare("a.txt", "text/plain ", encoded)
        assertNotNull(trailing)
        assertEquals(Kind.TEXT, trailing!!.kind)
        assertEquals("text/plain ", trailing.mimeType)
    }

    @Test
    fun `yaml mime is rejected`() {
        val encoded = Base64.getEncoder().encodeToString("a: 1".toByteArray(Charsets.UTF_8))
        assertNull(LocalAiFileAnalyzer.prepare("a.yaml", "application/yaml", encoded))
    }

    @Test
    fun `html mime is accepted as text`() {
        val encoded = Base64.getEncoder().encodeToString("<p>hi</p>".toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("a.html", "text/html", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals("<p>hi</p>", prepared.text)
    }

    @Test
    fun `json text is kept verbatim with surrounding spaces`() {
        val body = "  {\"a\": 1}  "
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("a.json", "application/json", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals(body, prepared.text)
    }

    @Test
    fun `uppercase json mime is lowercased and accepted`() {
        val encoded = Base64.getEncoder().encodeToString("{\"a\":1}".toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("a.json", "APPLICATION/JSON", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals("application/json", prepared.mimeType)
        assertEquals("{\"a\":1}", prepared.text)
    }

    @Test
    fun `svg image mime is rejected`() {
        val encoded = Base64.getEncoder().encodeToString("<svg/>".toByteArray(Charsets.UTF_8))
        assertNull(LocalAiFileAnalyzer.prepare("a.svg", "image/svg+xml", encoded))
    }

    @Test
    fun `uppercase xml mime is lowercased and accepted`() {
        val encoded = Base64.getEncoder().encodeToString("<a/>".toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("a.xml", "APPLICATION/XML", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals("application/xml", prepared.mimeType)
        assertEquals("<a/>", prepared.text)
    }

    @Test
    fun `text csv mime with parameters is accepted by prefix match`() {
        // text/ 前缀判定只看开头，带参数仍是文本；与 application/json 的精确相等判定不同。
        val encoded = Base64.getEncoder().encodeToString("a,b".toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("a.csv", "TEXT/CSV; CHARSET=UTF-8", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals("text/csv; charset=utf-8", prepared.mimeType)
        assertEquals("a,b", prepared.text)
    }

    @Test
    fun `uppercase json mime with parameters is rejected by exact match`() {
        val encoded = Base64.getEncoder().encodeToString("{\"a\":1}".toByteArray(Charsets.UTF_8))
        assertNull(LocalAiFileAnalyzer.prepare("a.json", "APPLICATION/JSON; CHARSET=UTF-8", encoded))
    }

    @Test
    fun `bom prefix text is kept verbatim`() {
        // BOM 不是空白字符，不触发 isBlank 拒绝，原样保留在文本开头。
        val body = "\uFEFFhi"
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("a.txt", "text/plain", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals(body, prepared.text)
    }

    @Test
    fun `del character text is accepted`() {
        // 拒绝集只有空字节与替换字符，DEL 不在其中。
        val body = "ab\u007Fc"
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("a.txt", "text/plain", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals(body, prepared.text)
    }
    @Test
    fun `malformed utf-8 bytes are rejected via replacement`() {
        val loneHigh = Base64.getEncoder().encodeToString(byteArrayOf(-1))
        assertNull(LocalAiFileAnalyzer.prepare("bad.txt", "text/plain", loneHigh))
        val loneLead = Base64.getEncoder().encodeToString(byteArrayOf(-61))
        assertNull(LocalAiFileAnalyzer.prepare("bad.txt", "text/plain", loneLead))
    }

    @Test
    fun `bare text slash mime is accepted by prefix match`() {
        val encoded = Base64.getEncoder().encodeToString("hi".toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("a.txt", "text/", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals("text/", prepared.mimeType)
    }

    @Test
    fun `nbsp-only text is rejected because nbsp counts as blank`() {
        // Kotlin 的 isBlank 把 NBSP 算作空白，单 NBSP 文本走 isBlank 拒绝（CI 实测）。
        val body = "\u00A0"
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        assertNull(LocalAiFileAnalyzer.prepare("a.txt", "text/plain", encoded))
    }

    @Test
    fun `mime decides text acceptance not the file extension`() {
        val json = Base64.getEncoder().encodeToString("{\"a\":1}".toByteArray(Charsets.UTF_8))
        val viaJsonMime = LocalAiFileAnalyzer.prepare("notes.txt", "application/json", json)
        assertNotNull(viaJsonMime)
        assertEquals(Kind.TEXT, viaJsonMime!!.kind)
        val plain = Base64.getEncoder().encodeToString("hi".toByteArray(Charsets.UTF_8))
        val viaPlainMime = LocalAiFileAnalyzer.prepare("data.json", "text/plain", plain)
        assertNotNull(viaPlainMime)
        assertEquals(Kind.TEXT, viaPlainMime!!.kind)
    }

    @Test
    fun `base64 with an embedded newline is rejected`() {
        val withNewline = "aGVs\nbG8="
        assertNull(LocalAiFileAnalyzer.prepare("a.txt", "text/plain", withNewline))
    }

    @Test
    fun `plain text keeps surrounding spaces verbatim`() {
        val body = "  hi  "
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("a.txt", "text/plain", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals(body, prepared.text)
    }

    @Test
    fun `markdown mime with parameters is accepted by prefix match`() {
        val encoded = Base64.getEncoder().encodeToString("hi".toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("a.md", "text/markdown; charset=utf-8", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals("text/markdown; charset=utf-8", prepared.mimeType)
        assertEquals("hi", prepared.text)
    }

    @Test
    fun `uppercase markdown mime is lowercased and accepted`() {
        val encoded = Base64.getEncoder().encodeToString("hi".toByteArray(Charsets.UTF_8))
        val prepared = LocalAiFileAnalyzer.prepare("a.md", "TEXT/MARKDOWN", encoded)
        assertNotNull(prepared)
        assertEquals(Kind.TEXT, prepared!!.kind)
        assertEquals("text/markdown", prepared.mimeType)
        assertEquals("hi", prepared.text)
    }

    @Test
    fun `xml mime with parameters is rejected by exact match`() {
        val encoded = Base64.getEncoder().encodeToString("<a/>".toByteArray(Charsets.UTF_8))
        assertNull(LocalAiFileAnalyzer.prepare("a.xml", "application/xml; charset=utf-8", encoded))
    }

    @Test
    fun `image mime with pdf magic bytes is sniffed as pdf`() {
        val encoded = Base64.getEncoder().encodeToString("%PDF-1.4 junk".toByteArray(Charsets.US_ASCII))
        assertNull(LocalAiFileAnalyzer.prepare("a.png", "image/png", encoded))
    }
}
