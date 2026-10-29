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

    @Test
    fun `empty string returns empty`() {
        assertEquals("", ChatTextDisplayPolicy.unescapeHtmlEntities(""))
    }

    @Test
    fun `lone ampersands and incomplete entities stay literal`() {
        // 没分号/没数字的都不是实体，不能动
        assertEquals("&", ChatTextDisplayPolicy.unescapeHtmlEntities("&"))
        assertEquals("a & b", ChatTextDisplayPolicy.unescapeHtmlEntities("a & b"))
        assertEquals("&#;", ChatTextDisplayPolicy.unescapeHtmlEntities("&#;"))
        assertEquals("&#x;", ChatTextDisplayPolicy.unescapeHtmlEntities("&#x;"))
        assertEquals("&amp", ChatTextDisplayPolicy.unescapeHtmlEntities("&amp"))
    }

    @Test
    fun `tab and emoji entities decode`() {
        assertEquals("a\tb", ChatTextDisplayPolicy.unescapeHtmlEntities("a&#9;b"))
        assertEquals("😀", ChatTextDisplayPolicy.unescapeHtmlEntities("&#128512;"))
    }

    @Test
    fun `adjacent numeric entities all decode`() {
        assertEquals("ABC", ChatTextDisplayPolicy.unescapeHtmlEntities("&#65;&#x42;&#67;"))
    }

    @Test
    fun `mixed sentence decodes in one pass`() {
        assertEquals(
            "1 < 2 && 3 > 2",
            ChatTextDisplayPolicy.unescapeHtmlEntities("1 &lt; 2 &amp;&amp; 3 &gt; 2"),
        )
    }

    @Test
    fun `numeric ampersand quote and carriage return decode`() {
        assertEquals("&", ChatTextDisplayPolicy.unescapeHtmlEntities("&#38;"))
        assertEquals("\"", ChatTextDisplayPolicy.unescapeHtmlEntities("&#34;"))
        assertEquals("\r", ChatTextDisplayPolicy.unescapeHtmlEntities("&#13;"))
    }

    @Test
    fun `leading zeros in numeric entities decode`() {
        assertEquals("A", ChatTextDisplayPolicy.unescapeHtmlEntities("&#065;"))
        assertEquals("A", ChatTextDisplayPolicy.unescapeHtmlEntities("&#x0041;"))
        assertEquals("A", ChatTextDisplayPolicy.unescapeHtmlEntities("&#0000065;"))
    }

    @Test
    fun `named entities are case sensitive`() {
        assertEquals("&AMP;", ChatTextDisplayPolicy.unescapeHtmlEntities("&AMP;"))
        assertEquals("&LT;", ChatTextDisplayPolicy.unescapeHtmlEntities("&LT;"))
        assertEquals("&NBSP;", ChatTextDisplayPolicy.unescapeHtmlEntities("&NBSP;"))
    }

    @Test
    fun `mixed case hex entities decode`() {
        assertEquals("JK", ChatTextDisplayPolicy.unescapeHtmlEntities("&#X4a;&#x4B;"))
        assertEquals("j", ChatTextDisplayPolicy.unescapeHtmlEntities("&#X6A;"))
    }

    @Test
    fun `double encoded numeric entity stays single decoded`() {
        assertEquals("&#65;", ChatTextDisplayPolicy.unescapeHtmlEntities("&amp;#65;"))
        assertEquals("&#x41;", ChatTextDisplayPolicy.unescapeHtmlEntities("&amp;#x41;"))
    }

}
