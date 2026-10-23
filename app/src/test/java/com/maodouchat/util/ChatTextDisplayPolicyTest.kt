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

    @Test
    fun `hex numeric entity decodes`() {
        assertEquals("AB", ChatTextDisplayPolicy.unescapeHtmlEntities("&#x41;&#X42;"))
    }

    @Test
    fun `out of range and malformed numerics stay literal`() {
        // 0 与 >0x10FFFF 不在合法区间；缺分号不匹配
        assertEquals("&#0;", ChatTextDisplayPolicy.unescapeHtmlEntities("&#0;"))
        assertEquals("&#x110000;", ChatTextDisplayPolicy.unescapeHtmlEntities("&#x110000;"))
        assertEquals("&#65", ChatTextDisplayPolicy.unescapeHtmlEntities("&#65"))
    }

    @Test
    fun `amp decodes last so double encoded stays single`() {
        assertEquals("&lt;", ChatTextDisplayPolicy.unescapeHtmlEntities("&amp;lt;"))
        assertEquals("&amp;", ChatTextDisplayPolicy.unescapeHtmlEntities("&amp;amp;"))
    }

    @Test
    fun `named entities decode`() {
        assertEquals(" \"''<>", ChatTextDisplayPolicy.unescapeHtmlEntities("&nbsp;&quot;&#39;&apos;&lt;&gt;"))
    }

    @Test
    fun `max code point boundary`() {
        // 0x10FFFF = 1114111 是最后一个合法码点
        assertEquals("\uDBFF\uDFFF", ChatTextDisplayPolicy.unescapeHtmlEntities("&#1114111;"))
        assertEquals("&#1114112;", ChatTextDisplayPolicy.unescapeHtmlEntities("&#1114112;"))
    }
}
