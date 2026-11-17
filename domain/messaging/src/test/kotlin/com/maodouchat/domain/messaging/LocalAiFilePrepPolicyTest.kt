package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalAiFilePrepPolicyTest {

    @Test
    fun `url-safe base64 becomes standard`() {
        assertEquals("Pz8/", LocalAiFilePrepPolicy.normalizeBase64("Pz8_"))
        assertEquals("ab+/==", LocalAiFilePrepPolicy.normalizeBase64("ab-_=="))
    }

    @Test
    fun `standard base64 is unchanged`() {
        assertEquals("aGVsbG8=", LocalAiFilePrepPolicy.normalizeBase64("aGVsbG8="))
    }

    @Test
    fun `pdf mime wins regardless of name`() {
        assertTrue(LocalAiFilePrepPolicy.isPdf("application/pdf", "a.bin", "hello".toByteArray()))
    }

    @Test
    fun `pdf extension wins regardless of mime`() {
        assertTrue(LocalAiFilePrepPolicy.isPdf("application/octet-stream", "a.PDF", "hello".toByteArray()))
    }

    @Test
    fun `pdf magic bytes win regardless of mime and name`() {
        val bytes = "%PDF-1.7 rest".toByteArray(Charsets.US_ASCII)
        assertTrue(LocalAiFilePrepPolicy.isPdf("application/octet-stream", "a.bin", bytes))
    }

    @Test
    fun `non-pdf bytes are not pdf`() {
        assertFalse(LocalAiFilePrepPolicy.isPdf("application/octet-stream", "a.bin", "hello".toByteArray()))
        assertFalse(LocalAiFilePrepPolicy.isPdf("text/plain", "a.txt", "%PD".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `text mime kinds`() {
        assertTrue(LocalAiFilePrepPolicy.isTextMime("text/plain"))
        assertTrue(LocalAiFilePrepPolicy.isTextMime("TEXT/PLAIN"))
        assertTrue(LocalAiFilePrepPolicy.isTextMime("application/json"))
        assertTrue(LocalAiFilePrepPolicy.isTextMime("application/xml"))
        assertTrue(LocalAiFilePrepPolicy.isTextMime("text/markdown"))
        assertTrue(LocalAiFilePrepPolicy.isTextMime("text/csv"))
        assertFalse(LocalAiFilePrepPolicy.isTextMime("application/octet-stream"))
        assertFalse(LocalAiFilePrepPolicy.isTextMime("application/pdf"))
    }

    @Test
    fun `blank text is unusable`() {
        assertFalse(LocalAiFilePrepPolicy.isUsableText("   "))
    }

    @Test
    fun `nul byte makes text unusable`() {
        assertFalse(LocalAiFilePrepPolicy.isUsableText("a\u0000b"))
    }

    @Test
    fun `replacement char makes text unusable`() {
        assertFalse(LocalAiFilePrepPolicy.isUsableText("a\uFFFDb"))
    }

    @Test
    fun `plain text is usable`() {
        assertTrue(LocalAiFilePrepPolicy.isUsableText("hello"))
    }

    @Test
    fun `long text truncates to MAX_TEXT_CHARS`() {
        val long = "x".repeat(LocalAiFilePrepPolicy.MAX_TEXT_CHARS + 5)
        assertEquals(LocalAiFilePrepPolicy.MAX_TEXT_CHARS, LocalAiFilePrepPolicy.truncateText(long).length)
    }

    @Test
    fun `short text passes through`() {
        assertEquals("hello", LocalAiFilePrepPolicy.truncateText("hello"))
    }

    @Test
    fun `blank file names fall back to defaults`() {
        assertEquals("document.pdf", LocalAiFilePrepPolicy.defaultPdfFileName(""))
        assertEquals("document.pdf", LocalAiFilePrepPolicy.defaultPdfFileName("   "))
        assertEquals("document.txt", LocalAiFilePrepPolicy.defaultTextFileName(""))
    }

    @Test
    fun `non-blank file names pass through`() {
        assertEquals("a.pdf", LocalAiFilePrepPolicy.defaultPdfFileName("a.pdf"))
        assertEquals("a.txt", LocalAiFilePrepPolicy.defaultTextFileName("a.txt"))
    }

    @Test
    fun `blank mime falls back to text plain`() {
        assertEquals("text/plain", LocalAiFilePrepPolicy.defaultTextMime(""))
        assertEquals("text/plain", LocalAiFilePrepPolicy.defaultTextMime("   "))
    }

    @Test
    fun `mime is lowercased`() {
        assertEquals("text/plain", LocalAiFilePrepPolicy.defaultTextMime("TEXT/PLAIN"))
    }
}
