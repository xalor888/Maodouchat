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

    @Test
    fun relativePathWithoutLeadingSlash_resolvesAgainstBase() {
        assertEquals(
            "https://chat.example.com/stickers/cat.webp",
            StickerSourcePolicy.resolve("stickers/cat.webp", base),
        )
    }

    @Test
    fun sameHostProtocolRelativeUrl_isAllowed() {
        assertEquals(
            "https://chat.example.com/a.webp",
            StickerSourcePolicy.resolve("//chat.example.com/a.webp", base),
        )
    }

    @Test
    fun baseWithPath_resolvesRelativeAgainstIt() {
        assertEquals(
            "https://chat.example.com/api/a.webp",
            StickerSourcePolicy.resolve("a.webp", "https://chat.example.com/api/"),
        )
    }

    @Test
    fun whitespaceAroundInputs_isTrimmed() {
        assertEquals(
            "https://chat.example.com/a.webp",
            StickerSourcePolicy.resolve("  /a.webp  ", "  https://chat.example.com  "),
        )
    }

    @Test
    fun uppercaseHostInUrl_isAccepted() {
        assertEquals(
            "https://chat.example.com/a.webp",
            StickerSourcePolicy.resolve("https://CHAT.EXAMPLE.COM/a.webp", base),
        )
    }

    @Test
    fun queryString_isPreserved() {
        assertEquals(
            "https://chat.example.com/a.webp?v=2",
            StickerSourcePolicy.resolve("/a.webp?v=2", base),
        )
    }

    @Test
    fun dotDotSegment_isNormalizedWithinSameOrigin() {
        assertEquals(
            "https://chat.example.com/a.webp",
            StickerSourcePolicy.resolve("/static/../a.webp", base),
        )
    }

    @Test
    fun dotDotAgainstBasePath_climbsOneLevel() {
        assertEquals(
            "https://chat.example.com/api/a.webp",
            StickerSourcePolicy.resolve("../a.webp", "https://chat.example.com/api/v1/"),
        )
    }

    @Test
    fun percentEncodedPath_isPreserved() {
        assertEquals(
            "https://chat.example.com/a%20b.webp",
            StickerSourcePolicy.resolve("/a%20b.webp", base),
        )
    }

    @Test
    fun usernameOnly_isRejected() {
        assertNull(StickerSourcePolicy.resolve("https://user@chat.example.com/a.webp", base))
    }


    @Test
    fun rootBase_resolvesBareRelative() {
        assertEquals(
            "https://chat.example.com/a.webp",
            StickerSourcePolicy.resolve("a.webp", "https://chat.example.com"),
        )
    }

    @Test
    fun passwordOnly_isRejected() {
        assertNull(StickerSourcePolicy.resolve("https://:pass@chat.example.com/a.webp", base))
    }

    @Test
    fun baseQuery_isDroppedForAbsolutePath() {
        assertEquals(
            "https://chat.example.com/a.webp",
            StickerSourcePolicy.resolve("/a.webp", "https://chat.example.com/?token=1"),
        )
    }

    @Test
    fun uppercaseScheme_isAcceptedAndNormalized() {
        assertEquals(
            "https://chat.example.com/a.webp",
            StickerSourcePolicy.resolve("HTTPS://chat.example.com/a.webp", base),
        )
    }

    @Test
    fun singleDotSegment_isNormalized() {
        assertEquals(
            "https://chat.example.com/static/a.webp",
            StickerSourcePolicy.resolve("/static/./a.webp", base),
        )
    }
}
