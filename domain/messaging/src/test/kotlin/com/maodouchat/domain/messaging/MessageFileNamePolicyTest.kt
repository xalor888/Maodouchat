package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageFileNamePolicyTest {

    @Test
    fun `http uri returns last path segment`() {
        assertEquals("photo.jpg", MessageFileNamePolicy.fileNameFromUri("https://example.com/dir/photo.jpg"))
    }

    @Test
    fun `file uri returns last path segment`() {
        assertEquals("c.png", MessageFileNamePolicy.fileNameFromUri("file:///a/b/c.png"))
    }

    @Test
    fun `trailing slash has no file name`() {
        assertNull(MessageFileNamePolicy.fileNameFromUri("https://example.com/a/b/"))
    }

    @Test
    fun `uri without path has no file name`() {
        assertNull(MessageFileNamePolicy.fileNameFromUri("https://example.com"))
    }

    @Test
    fun `invalid uri returns null`() {
        assertNull(MessageFileNamePolicy.fileNameFromUri("::bad::"))
    }

    @Test
    fun `empty uri returns null`() {
        assertNull(MessageFileNamePolicy.fileNameFromUri(""))
    }
}
