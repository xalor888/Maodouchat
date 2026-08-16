package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPreviewPolicyTest {

    @Test
    fun firstHttpUrl_extractsAndNormalizes() {
        assertEquals(
            "https://example.com/a",
            LinkPreviewPolicy.firstHttpUrl("see https://example.com/a please"),
        )
        assertEquals(
            "https://www.example.com/x",
            LinkPreviewPolicy.firstHttpUrl("www.example.com/x"),
        )
        assertNull(LinkPreviewPolicy.firstHttpUrl("no links here"))
        assertNull(LinkPreviewPolicy.firstHttpUrl("ftp://example.com"))
    }

    @Test
    fun sanitizeUrl_rejectsLocal() {
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://localhost/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://127.0.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://127.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://127.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://2130706433/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://0x7f000001/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://0177.0.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://0x7f.0.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://[::]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://[0:0:0:0:0:0:0:1]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://[::127.0.0.1]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://[::ffff:127.0.0.1]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://[fe80::1]/a"))
        assertNotNull(LinkPreviewPolicy.sanitizeUrl("https://example.com/path"))
        assertNotNull(LinkPreviewPolicy.sanitizeUrl("http://1.2.3.4/path"))
        assertNotNull(LinkPreviewPolicy.sanitizeUrl("http://[2001:4860:4860::8888]/path"))
    }

    @Test
    fun parseHtmlPreview_readsOgTags() {
        val html = """
            <html><head>
            <meta property="og:title" content="Hello &amp; World" />
            <meta property="og:description" content="Desc" />
            <meta property="og:image" content="/img.png" />
            <meta property="og:site_name" content="Example" />
            <title>Fallback</title>
            </head><body></body></html>
        """.trimIndent()
        val p = LinkPreviewPolicy.parseHtmlPreview("https://example.com/page", html)
        assertEquals("Hello & World", p.title)
        assertEquals("Desc", p.description)
        assertEquals("https://example.com/img.png", p.imageUrl)
        assertEquals("Example", p.siteName)
        assertTrue(LinkPreviewPolicy.isUseful(p))
        assertFalse(
            LinkPreviewPolicy.isUseful(
                LinkPreviewPolicy.Preview("https://x.com", null, null, null, null)
            )
        )
    }

    @Test
    fun parseHtmlPreview_titleFallback() {
        val html = "<html><head><title> Only Title </title></head></html>"
        val p = LinkPreviewPolicy.parseHtmlPreview("https://example.com", html)
        assertEquals("Only Title", p.title)
    }
}
