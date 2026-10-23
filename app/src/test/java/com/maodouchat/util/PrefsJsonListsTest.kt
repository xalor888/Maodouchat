package com.maodouchat.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrefsJsonListsTest {

    @Test
    fun roundTrip() {
        val items = listOf("a", "b-c_1", "中文", "x".repeat(200))
        assertEquals(items, PrefsJsonLists.decode(PrefsJsonLists.encode(items)))
    }

    @Test
    fun emptyRoundTrip() {
        assertEquals("[]", PrefsJsonLists.encode(emptyList()))
        assertTrue(PrefsJsonLists.decode("[]").isEmpty())
    }

    @Test
    fun decodeTrimsAndDropsBlanks() {
        assertEquals(
            listOf("a", "b"),
            PrefsJsonLists.decode("""["  a  ", "", "   ", "b"]"""),
        )
    }

    @Test
    fun corruptInputDecodesToEmpty() {
        assertTrue(PrefsJsonLists.decode("").isEmpty())
        assertTrue(PrefsJsonLists.decode("not-json{{{").isEmpty())
        assertTrue(PrefsJsonLists.decode("{\"k\":1}").isEmpty())
        assertTrue(PrefsJsonLists.decode("null").isEmpty())
    }

    @Test
    fun escapedCharactersRoundTrip() {
        // JSON 转义（引号/反斜杠/换行/制表符）必须原样回来。
        val items = listOf("a\"b", "c\\d", "e\nf", "g\th")
        assertEquals(items, PrefsJsonLists.decode(PrefsJsonLists.encode(items)))
    }

    @Test
    fun unicodeAndEmojiRoundTrip() {
        val items = listOf("中文", "🎉🎊", "mixed-中英-123")
        assertEquals(items, PrefsJsonLists.decode(PrefsJsonLists.encode(items)))
    }

    @Test
    fun bracketsInsideStringsSurvive() {
        // 方括号是 JSON 的结构字符，出现在字符串里不能被当成分隔。
        val items = listOf("a[b]", "c]d", "[e]")
        assertEquals(items, PrefsJsonLists.decode(PrefsJsonLists.encode(items)))
    }

    @Test
    fun nonStringElementsAreReadAsStrings() {
        // optString 语义：数字/布尔按字面读，null 按空串（随即被过滤）。
        assertEquals(
            listOf("1", "true", "a"),
            PrefsJsonLists.decode("[1, true, null, \"a\"]"),
        )
    }

    @Test
    fun encodeIsDeterministicAndHandlesLargeLists() {
        val items = List(1000) { "item-$it" }
        val first = PrefsJsonLists.encode(items)
        assertEquals(first, PrefsJsonLists.encode(items), "同输入必须同输出")
        assertEquals(items, PrefsJsonLists.decode(first), "千项列表往返不丢")
    }
}
