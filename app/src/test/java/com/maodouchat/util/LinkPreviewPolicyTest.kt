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
    fun sanitizeUrl_rejectsLocalhostAndInternalDomains() {
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://localhost/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://localhost:8080/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://myhost.local/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://service.internal/a"))
    }

    @Test
    fun sanitizeUrl_rejectsPrivateIpv4Ranges() {
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://127.0.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://10.0.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://172.16.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://172.31.255.255/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://192.168.1.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://169.254.169.254/latest/meta-data"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://100.64.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://0.0.0.0/a"))
    }

    @Test
    fun sanitizeUrl_rejectsIpv4EncodingVariants() {
        // Decimal / Hex integer literals
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://2130706433/a")) // 127.0.0.1
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://0x7f000001/a")) // 127.0.0.1

        // Dotted shorthand
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://127.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://127.0.1/a"))

        // Octal / leading zeroes
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://0177.0.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://00.1.2.3/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://1.00.2.3/a"))

        // Hex in dotted labels
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://0x7f.0.0.1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://127.0.0.0x1/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://192.168.0.0x1/a"))
    }

    @Test
    fun sanitizeUrl_rejectsNonPublicIpv6Variants() {
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://[::]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://[::1]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://[0:0:0:0:0:0:0:1]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://[::127.0.0.1]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://[::ffff:127.0.0.1]/a"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://[fe80::1]/a")) // Link-local
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://[fd00::1]/a")) // Unique Local (ULA)
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://[fc00::1]/a")) // Unique Local (ULA)
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://[2001:db8::1]/a")) // Documentation
    }

    @Test
    fun sanitizeUrl_rejectsUserInfoAndPercentEncoding() {
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://user:password@example.com/"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://admin@localhost/"))
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://%31%32%37.0.0.1/"))
    }

    @Test
    fun sanitizeUrl_enforcesPortRestrictions() {
        // Standard ports allowed
        assertNotNull(LinkPreviewPolicy.sanitizeUrl("https://example.com:443/test"))
        assertNotNull(LinkPreviewPolicy.sanitizeUrl("http://example.com:80/test"))
        assertNotNull(LinkPreviewPolicy.sanitizeUrl("http://example.com:8080/test"))
        assertNotNull(LinkPreviewPolicy.sanitizeUrl("https://example.com:8443/test"))

        // Non-standard / service ports blocked
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://example.com:22/")) // SSH
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://example.com:25/")) // SMTP
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://example.com:6379/")) // Redis
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://example.com:3306/")) // MySQL
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://example.com:2375/")) // Docker
    }

    @Test
    fun sanitizeUrl_allowsValidPublicTargets() {
        assertNotNull(LinkPreviewPolicy.sanitizeUrl("https://example.com/path"))
        assertNotNull(LinkPreviewPolicy.sanitizeUrl("http://1.2.3.4/path"))
        assertNotNull(LinkPreviewPolicy.sanitizeUrl("http://8.8.8.8/path"))
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
                Preview("https://x.com", null, null, null, null)
            )
        )
    }

    @Test
    fun parseHtmlPreview_titleFallback() {
        val html = "<html><head><title> Only Title </title></head></html>"
        val p = LinkPreviewPolicy.parseHtmlPreview("https://example.com", html)
        assertEquals("Only Title", p.title)
    }

    @Test
    fun sanitizeUrl_rejectsReservedAndDocumentationRanges() {
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://192.0.0.1/a")) // IETF 协议分配
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://192.0.2.1/a")) // TEST-NET-1
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://198.51.100.1/a")) // TEST-NET-2
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://203.0.113.1/a")) // TEST-NET-3
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://198.18.0.1/a")) // 基准测试网段
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://224.0.0.1/a")) // 组播
        assertNull(LinkPreviewPolicy.sanitizeUrl("http://240.0.0.1/a")) // 保留段
    }

    @Test
    fun sanitizeUrl_allowsPublicIpsAtPrivateRangeBoundaries() {
        // 私有网段边界外的相邻地址仍是公网，必须放行
        assertNotNull(LinkPreviewPolicy.sanitizeUrl("http://1.0.0.1/a"))
        assertNotNull(LinkPreviewPolicy.sanitizeUrl("http://9.9.9.9/a"))
        assertNotNull(LinkPreviewPolicy.sanitizeUrl("http://172.15.255.255/a"))
        assertNotNull(LinkPreviewPolicy.sanitizeUrl("http://172.32.0.1/a"))
        assertNotNull(LinkPreviewPolicy.sanitizeUrl("http://100.63.255.255/a"))
        assertNotNull(LinkPreviewPolicy.sanitizeUrl("http://100.128.0.1/a"))
    }

    @Test
    fun firstHttpUrl_usesFirstMatchAndTrimsTrailingPunctuation() {
        assertEquals(
            "https://a.com/x",
            LinkPreviewPolicy.firstHttpUrl("see https://a.com/x and https://b.com/y"),
        )
        assertEquals(
            "https://a.com/x",
            LinkPreviewPolicy.firstHttpUrl("(see https://a.com/x)."),
        )
        assertEquals(
            "https://a.com/x",
            LinkPreviewPolicy.firstHttpUrl("see https://a.com/x, then more"),
        )
    }

    @Test
    fun firstHttpUrl_stopsBeforeLiteralMetaTag() {
        // 正文里出现字面 <meta> 即截断：后面的 URL 不取
        assertEquals(
            "https://good.com/a",
            LinkPreviewPolicy.firstHttpUrl("https://good.com/a <meta> https://evil.com/b"),
        )
    }

    @Test
    fun parseHtmlPreview_twitterTitleFallbackWithLengthCaps() {
        val html = "<html><head><meta name=\"twitter:title\" content=\"Tw Title\"/></head></html>"
        val p = LinkPreviewPolicy.parseHtmlPreview("https://example.com", html)
        assertEquals("Tw Title", p.title)

        val capped = "<html><head>" +
            "<meta property=\"og:title\" content=\"" + "t".repeat(300) + "\"/>" +
            "<meta property=\"og:description\" content=\"" + "d".repeat(500) + "\"/>" +
            "<meta property=\"og:site_name\" content=\"" + "s".repeat(200) + "\"/>" +
            "</head></html>"
        val q = LinkPreviewPolicy.parseHtmlPreview("https://example.com", capped)
        assertEquals(220, q.title?.length)
        assertEquals(400, q.description?.length)
        assertEquals(120, q.siteName?.length)
    }
}
