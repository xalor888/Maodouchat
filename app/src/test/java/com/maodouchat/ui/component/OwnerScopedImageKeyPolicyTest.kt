package com.maodouchat.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnerScopedImageKeyPolicyTest {
    @Test
    fun `same url different owners produce different keys`() {
        val url = "https://cdn.example/post/1.jpg"
        val a = OwnerScopedImageKeys.formatKey("user-a", url)
        val b = OwnerScopedImageKeys.formatKey("user-b", url)
        assertEquals("user-a:$url", a)
        assertEquals("user-b:$url", b)
        assertNotEquals(a, b)
    }

    @Test
    fun `blank data yields null`() {
        assertNull(OwnerScopedImageKeys.formatKey("user-a", ""))
        assertNull(OwnerScopedImageKeys.formatKey("user-a", "   "))
    }

    @Test
    fun `empty owner still prefixes for isolation from raw url`() {
        val url = "file:///data/media.jpg"
        assertEquals(":$url", OwnerScopedImageKeys.formatKey("", url))
    }
    @Test
    fun `owner containing a colon is kept verbatim`() {
        val url = "https://cdn.example/post/1.jpg"
        assertEquals("a:b:$url", OwnerScopedImageKeys.formatKey("a:b", url))
    }

    @Test
    fun `blank owner still prefixes when data is non-blank`() {
        val url = "https://cdn.example/post/1.jpg"
        assertEquals("   :$url", OwnerScopedImageKeys.formatKey("   ", url))
    }

    @Test
    fun `padded data is not trimmed`() {
        assertEquals("u1: url ", OwnerScopedImageKeys.formatKey("u1", " url "))
    }

    @Test
    fun `query string in data is kept verbatim`() {
        val url = "https://cdn.example/a.jpg?v=1&x=2"
        assertEquals("u1:$url", OwnerScopedImageKeys.formatKey("u1", url))
    }

    @Test
    fun `same owner and data is deterministic and data-sensitive`() {
        val url = "https://cdn.example/post/1.jpg"
        assertEquals(
            OwnerScopedImageKeys.formatKey("u1", url),
            OwnerScopedImageKeys.formatKey("u1", url),
        )
        assertNotEquals(
            OwnerScopedImageKeys.formatKey("u1", url),
            OwnerScopedImageKeys.formatKey("u1", "https://cdn.example/post/2.jpg"),
        )
    }

}
