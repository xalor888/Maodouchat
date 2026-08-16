package com.maodouchat.slim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StickerSourcePolicyTest {

    private val base = "https://chat.example.com"

    @Test
    fun relativePath_isResolvedAgainstBase() {
        assertEquals(
            "https://chat.example.com/static/stickers/cute/cat.webp",
            StickerSourcePolicy.resolve("/static/stickers/cute/cat.webp", base),
        )
    }

    @Test
    fun sameOriginAbsoluteUrl_isAllowed() {
        assertEquals(
            "https://chat.example.com/static/stickers/cute/cat.webp",
            StickerSourcePolicy.resolve("https://chat.example.com/static/stickers/cute/cat.webp", base),
        )
    }

    @Test
    fun crossOriginUrl_isRejected() {
        assertNull(StickerSourcePolicy.resolve("https://evil.example.com/a.webp", base))
        assertNull(StickerSourcePolicy.resolve("//evil.example.com/a.webp", base))
    }

    @Test
    fun differentPortOrScheme_isRejected() {
        assertNull(StickerSourcePolicy.resolve("https://chat.example.com:8443/a.webp", base))
        assertNull(StickerSourcePolicy.resolve("http://chat.example.com/a.webp", base))
    }

    @Test
    fun credentialsOrFragment_isRejected() {
        assertNull(StickerSourcePolicy.resolve("https://user:pass@chat.example.com/a.webp", base))
        assertNull(StickerSourcePolicy.resolve("https://chat.example.com/a.webp#fragment", base))
    }

    @Test
    fun invalidInput_isRejected() {
        assertNull(StickerSourcePolicy.resolve("", base))
        assertNull(StickerSourcePolicy.resolve("javascript:alert(1)", base))
        assertNull(StickerSourcePolicy.resolve("/a.webp", "not-a-url"))
    }
}
