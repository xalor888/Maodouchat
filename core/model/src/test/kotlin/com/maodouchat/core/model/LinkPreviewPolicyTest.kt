package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPreviewPolicyTest {

    @Test
    fun `firstHttpUrl extracts https url and normalizes www`() {
        assertEquals(
            "https://example.com/a",
            LinkPreviewPolicy.firstHttpUrl("see https://example.com/a please")
        )
        assertEquals(
            "https://www.example.com/x",
            LinkPreviewPolicy.firstHttpUrl("www.example.com/x")
        )
        assertNull(LinkPreviewPolicy.firstHttpUrl("no links here"))
        assertNull(LinkPreviewPolicy.firstHttpUrl("ftp://example.com/f"))
    }

    @Test
    fun `sanitizeUrl rejects loopback and private hosts`() {
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://localhost/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://127.0.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://192.168.1.5/a"))
        assertEquals(
            "https://example.com/a",
            LinkPreviewPolicy.sanitizeUrl("https://example.com/a")
        )
    }

    @Test
    fun `parseHtmlPreview picks og title and resolves relative image`() {
        val preview = LinkPreviewPolicy.parseHtmlPreview(
            "https://example.com/a",
            "<html><head><meta property=\"og:title\" content=\"Hello\" />" +
                "<meta property=\"og:image\" content=\"/img.png\" /></head></html>"
        )
        assertEquals("Hello", preview.title)
        assertEquals("https://example.com/img.png", preview.imageUrl)
        assertTrue(LinkPreviewPolicy.isUseful(preview))
    }
}
