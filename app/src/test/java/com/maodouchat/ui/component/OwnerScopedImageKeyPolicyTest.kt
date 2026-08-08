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
}
