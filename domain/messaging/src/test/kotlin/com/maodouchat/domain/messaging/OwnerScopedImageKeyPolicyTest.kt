package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OwnerScopedImageKeyPolicyTest {
    @Test
    fun `same url different owners produce different keys`() {
        val url = "https://cdn.example/post/1.jpg"
        val a = OwnerScopedImageKeyPolicy.formatKey("user-a", url)
        val b = OwnerScopedImageKeyPolicy.formatKey("user-b", url)
        assertEquals("user-a:$url", a)
        assertEquals("user-b:$url", b)
        assertNotEquals(a, b)
    }

    @Test
    fun `blank data yields null`() {
        assertNull(OwnerScopedImageKeyPolicy.formatKey("user-a", ""))
        assertNull(OwnerScopedImageKeyPolicy.formatKey("user-a", "   "))
    }

    @Test
    fun `empty owner still prefixes for isolation from raw url`() {
        val url = "file:///data/media.jpg"
        assertEquals(":$url", OwnerScopedImageKeyPolicy.formatKey("", url))
    }
    @Test
    fun `owner containing a colon is kept verbatim`() {
        val url = "https://cdn.example/post/1.jpg"
        assertEquals("a:b:$url", OwnerScopedImageKeyPolicy.formatKey("a:b", url))
    }

    @Test
    fun `blank owner still prefixes when data is non-blank`() {
        val url = "https://cdn.example/post/1.jpg"
        assertEquals("   :$url", OwnerScopedImageKeyPolicy.formatKey("   ", url))
    }

    @Test
    fun `padded data is not trimmed`() {
        assertEquals("u1: url ", OwnerScopedImageKeyPolicy.formatKey("u1", " url "))
    }

    @Test
    fun `query string in data is kept verbatim`() {
        val url = "https://cdn.example/a.jpg?v=1&x=2"
        assertEquals("u1:$url", OwnerScopedImageKeyPolicy.formatKey("u1", url))
    }

    @Test
    fun `same owner and data is deterministic and data-sensitive`() {
        val url = "https://cdn.example/post/1.jpg"
        assertEquals(
            OwnerScopedImageKeyPolicy.formatKey("u1", url),
            OwnerScopedImageKeyPolicy.formatKey("u1", url),
        )
        assertNotEquals(
            OwnerScopedImageKeyPolicy.formatKey("u1", url),
            OwnerScopedImageKeyPolicy.formatKey("u1", "https://cdn.example/post/2.jpg"),
        )
    }

    @Test
    fun `tab and newline only data yields null`() {
        assertNull(OwnerScopedImageKeyPolicy.formatKey("u1", "\t \n"))
        assertNull(OwnerScopedImageKeyPolicy.formatKey("u1", "\n"))
    }

    @Test
    fun `colons inside data are kept verbatim`() {
        assertEquals("u1:a:b:c", OwnerScopedImageKeyPolicy.formatKey("u1", "a:b:c"))
        assertEquals("a:b:x:y", OwnerScopedImageKeyPolicy.formatKey("a:b", "x:y"))
    }

    @Test
    fun `blank data wins over empty owner`() {
        assertNull(OwnerScopedImageKeyPolicy.formatKey("", "   "))
        assertNull(OwnerScopedImageKeyPolicy.formatKey("", ""))
    }

    @Test
    fun `unicode and inner newline in data are kept verbatim`() {
        assertEquals("u1:你好🌍", OwnerScopedImageKeyPolicy.formatKey("u1", "你好🌍"))
        assertEquals("u1:line1\nline2", OwnerScopedImageKeyPolicy.formatKey("u1", "line1\nline2"))
    }

    @Test
    fun `long data is not truncated`() {
        val long = "x".repeat(5000)
        assertEquals("u1:$long", OwnerScopedImageKeyPolicy.formatKey("u1", long))
    }
}
