package com.maodouchat.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G152：`TextMessageBubble.findUrlRanges` 的测试。
 *
 * 它是一个手写扫描器：找到 `http://` / `https://` 起点后一直吃到空白或
 * `<>"'`，再把结尾的 `.,;:!?)]}` 逐个剥掉。
 * 两个易错点：**结尾标点剥离**（否则链接点击会把句号带进去）与
 * **剥到只剩标点时退化为不含标点的范围**。
 */
class FindUrlRangesTest {

    private fun ranges(text: String) = findUrlRanges(text)

    private fun sub(text: String, r: Pair<Int, Int>) = text.substring(r.first, r.second)

    @Test
    fun `single http url is found`() {
        val text = "see http://a.com now"
        assertEquals(listOf(4 to 16), ranges(text))
        assertEquals("http://a.com", sub(text, ranges(text).single()))
    }

    @Test
    fun `https url is found too`() {
        val text = "https://a.com/x?y=1"
        assertEquals(listOf(0 to 19), ranges(text))
        assertEquals("https://a.com/x?y=1", sub(text, ranges(text).single()))
    }

    @Test
    fun `multiple urls on one line`() {
        val text = "a http://x.com b https://y.com c"
        assertEquals(listOf("http://x.com", "https://y.com"), ranges(text).map { sub(text, it) })
    }

    @Test
    fun `trailing punctuation is stripped`() {
        // 每一种结尾标点都要被剥掉
        listOf(".", ",", ";", ":", "!", "?", ")", "]", "}").forEach { p ->
            val text = "go http://a.com$p done"
            val r = ranges(text)
            assertEquals("标点 $p 没被剥掉", 1, r.size)
            assertEquals("http://a.com", sub(text, r.single()))
        }
    }

    @Test
    fun `several trailing punctuation marks are all stripped`() {
        val text = "go http://a.com!!! done"
        assertEquals("http://a.com", sub(text, ranges(text).single()))
    }

    @Test
    fun `angle brackets and quotes end the url`() {
        listOf("<", ">", "\"", "'").forEach { stop ->
            val text = "go http://a.com${stop}x done"
            assertEquals("结束符 $stop 不生效", "http://a.com", sub(text, ranges(text).single()))
        }
    }

    @Test
    fun `url that is all punctuation degenerates to a punctuation free range`() {
        // "http://" 后面紧跟标点：剥到 start+7 就停，范围仍以 "http://" 开头但不含标点
        val text = "x http://. y"
        val r = ranges(text)
        assertEquals(1, r.size)
        assertEquals("http://", sub(text, r.single()))
    }

    @Test
    fun `no url returns empty`() {
        assertTrue(ranges("plain text").isEmpty())
        assertTrue(ranges("http:/missing-slash").isEmpty())
        assertTrue(ranges("").isEmpty())
    }

    @Test
    fun `url at the very start and end of the text`() {
        val text = "http://a.com"
        assertEquals(listOf(0 to 12), ranges(text))
        val text2 = "visit http://a.com"
        assertEquals(listOf(6 to 18), ranges(text2))
    }

    @Test
    fun `adjacent urls separated by one space`() {
        val text = "http://a.com http://b.com"
        assertEquals(listOf("http://a.com", "http://b.com"), ranges(text).map { sub(text, it) })
    }
}
