package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class ReplyPreviewPolicyTest {

    @Test
    fun `short text passes through`() {
        assertEquals("hello", ReplyPreviewPolicy.preview("hello"))
    }

    @Test
    fun `exactly 60 chars passes through`() {
        val text = "a".repeat(60)
        assertEquals(text, ReplyPreviewPolicy.preview(text))
    }

    @Test
    fun `longer text truncates to 60 chars`() {
        val text = "a".repeat(61)
        assertEquals("a".repeat(60), ReplyPreviewPolicy.preview(text))
    }

    @Test
    fun `empty text stays empty`() {
        assertEquals("", ReplyPreviewPolicy.preview(""))
    }
}
