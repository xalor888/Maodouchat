package com.maodouchat.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTextDisplayPolicyTest {
    @Test
    fun `numeric newline entity becomes a real newline`() {
        val raw = "访问链接加入云湖群聊【Maodouchat】&#10;https://example.com/share"
        val out = ChatTextDisplayPolicy.unescapeHtmlEntities(raw)
        assertEquals("访问链接加入云湖群聊【Maodouchat】\nhttps://example.com/share", out)
    }

    @Test
    fun `ampersand entity decodes after numeric entities`() {
        val raw = "a&#10;b &amp; c"
        assertEquals("a\nb & c", ChatTextDisplayPolicy.unescapeHtmlEntities(raw))
    }

    @Test
    fun `plain text is unchanged`() {
        assertEquals("hello_live_sweep", ChatTextDisplayPolicy.unescapeHtmlEntities("hello_live_sweep"))
    }
}
