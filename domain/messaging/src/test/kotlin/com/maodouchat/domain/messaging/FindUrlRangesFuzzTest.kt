package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * findUrlRanges 区间 fuzz（清单 931 行第十四块，簇 4）。
 *
 * 源码实测的不变式（只对小写 http:// 与 https:// 匹配）：
 * - 区间有序且不交；每段子串以小写 scheme 开头；
 * - 区间恒含至少 scheme 本身（下限 start+7/start+8，因 `/` 不在剥离集）；
 * - 区间尾字符不在剥离集 {., ,, ;, :, !, ?, ), ], } 内（`(`、`[`、`{` 永不剥离）；
 * - 区间内无空白与 `<>"'`（扫描到这些字符即终止）；
 * - 词中 URL 也能命中（如 `xhttp://a.com` 从下标 1 起）。
 */
class FindUrlRangesFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 200
        private val stripChars = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
        private val terminators = setOf('<', '>', '"', '\'')
        private val fragments = listOf(
            "http://", "https://", "HTTP://", "Http://", "hTTp://", "HTTPS://",
            "a.com", "x", "www.example.com/path?q=1", "。", "，", "chat", "see ", " ",
            "\n", "\t", "(", ")", "[", "]", "{", "}", "<", ">", "\"", "'", ".", ",", ";",
            ":", "!", "?", "中", "文", "🎉", "end", "http", "://", "http:/", "ttp://"
        )
    }

    private fun randomText(random: Random): String {
        val count = random.nextInt(0, 24)
        return buildString {
            repeat(count) { append(fragments[random.nextInt(fragments.size)]) }
        }
    }

    private fun assertRangesValid(text: String, ranges: List<Pair<Int, Int>>, iteration: Int) {
        var previousEnd = 0
        ranges.forEach { (start, end) ->
            assertTrue(start >= previousEnd, "fuzz 迭代 #$iteration：区间必须有序不交，文本 ${text.take(60)}")
            assertTrue(start >= 0 && end <= text.length, "fuzz 迭代 #$iteration：区间不得越界")
            assertTrue(end > start, "fuzz 迭代 #$iteration：区间不得为空")
            val sub = text.substring(start, end)
            assertTrue(
                sub.startsWith("http://") || sub.startsWith("https://"),
                "fuzz 迭代 #$iteration：区间子串必须以小写 scheme 开头，实际 $sub"
            )
            assertTrue(end - start >= 7, "fuzz 迭代 #$iteration：区间恒含至少 scheme 本身")
            assertTrue(
                sub.last() !in stripChars,
                "fuzz 迭代 #$iteration：区间尾字符必须已剥净，实际 $sub"
            )
            assertTrue(
                sub.none { it.isWhitespace() || it in terminators },
                "fuzz 迭代 #$iteration：区间内不得含空白或终止符，实际 $sub"
            )
            previousEnd = end
        }
    }

    @Test
    fun `randomTextRangesAreOrderedNonOverlappingAndWellFormed`() {
        val random = Random(0x6F_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val text = randomText(random)
            assertRangesValid(text, findUrlRanges(text), i)
        }
    }

    @Test
    fun `findingRangesIsDeterministic`() {
        val random = Random(0x6F_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val text = randomText(random)
            assertEquals(
                findUrlRanges(text), findUrlRanges(text),
                "fuzz 迭代 #$i：同一文本两次扫描必须相同"
            )
        }
    }

    @Test
    fun `textWithoutLowercaseSchemeHasNoRanges`() {
        val random = Random(0x6F_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            // 只用不可能拼出小写 scheme 的碎片拼文本，必然零区间：除完整 scheme
            // 外，"http"、"://"、"http:/"、"ttp://" 这些可拼接成 scheme 的碎片也要排除。
            val safeFragments = fragments.filter {
                !it.contains("http") && !it.contains("://") && !it.contains("ttp://")
            }
            val text = buildString {
                repeat(random.nextInt(0, 24)) { append(safeFragments[random.nextInt(safeFragments.size)]) }
            }
            assertTrue(
                findUrlRanges(text).isEmpty(),
                "fuzz 迭代 #$i：无小写 scheme 的文本不得有区间，文本 ${text.take(60)}"
            )
        }
        assertTrue(findUrlRanges("").isEmpty())
        assertTrue(findUrlRanges("plain text").isEmpty())
        assertTrue(findUrlRanges("HTTP://a.com").isEmpty(), "大写 scheme 不匹配")
        assertTrue(findUrlRanges("Http://a.com").isEmpty(), "首字母大写 scheme 不匹配")
    }

    @Test
    fun `uppercaseSchemeVariantsNeverMatch`() {
        listOf("HTTP://", "Http://", "hTTp://", "HTTPS://", "Https://").forEach { scheme ->
            val text = "see ${scheme}a.com now"
            assertTrue(
                findUrlRanges(text).isEmpty(),
                "变体 scheme $scheme 不得匹配"
            )
        }
    }

    @Test
    fun `punctuationBoundaries`() {
        // 剥离集逐个钉住；( [ { 不剥离；< > " ' 终止扫描。
        assertEquals(listOf(0 to 12), findUrlRanges("http://a.com."))
        assertEquals(listOf(0 to 12), findUrlRanges("http://a.com,"))
        assertEquals(listOf(0 to 12), findUrlRanges("http://a.com;"))
        assertEquals(listOf(0 to 12), findUrlRanges("http://a.com:"))
        assertEquals(listOf(0 to 12), findUrlRanges("http://a.com!"))
        assertEquals(listOf(0 to 12), findUrlRanges("http://a.com?"))
        assertEquals(listOf(0 to 12), findUrlRanges("http://a.com)"))
        assertEquals(listOf(0 to 12), findUrlRanges("http://a.com]"))
        assertEquals(listOf(0 to 12), findUrlRanges("http://a.com}"))
        assertEquals(listOf(0 to 12), findUrlRanges("http://a.com!!!"))
        assertEquals(listOf(0 to 13), findUrlRanges("http://a.com("))
        assertEquals(listOf(0 to 13), findUrlRanges("http://a.com["))
        assertEquals(listOf(0 to 13), findUrlRanges("http://a.com{"))
        assertEquals(listOf(0 to 12), findUrlRanges("http://a.com<x"))
        assertEquals(listOf(0 to 12), findUrlRanges("http://a.com>x"))
        assertEquals(listOf(0 to 12), findUrlRanges("http://a.com\"x"))
        assertEquals(listOf(0 to 12), findUrlRanges("http://a.com'x"))
        // 退化情形：区间恰为 scheme 本身；词中 URL 从 scheme 起命中。
        assertEquals(listOf(0 to 7), findUrlRanges("http://"))
        assertEquals(listOf(0 to 8), findUrlRanges("https://"))
        assertEquals(listOf(0 to 7), findUrlRanges("http://..."))
        assertEquals(listOf(1 to 13), findUrlRanges("xhttp://a.com"))
        // 多 URL；无分隔符时第一段扫描会吞掉整串，只有一个区间。
        assertEquals(listOf(0 to 12, 13 to 25), findUrlRanges("http://a.com http://b.com"))
    }

    @Test
    fun `multipleUrlsAndAdjacentText`() {
        assertEquals(listOf(0 to 12, 13 to 25), findUrlRanges("http://a.com http://b.com"))
        assertEquals(listOf(4 to 16, 21 to 33), findUrlRanges("see http://a.com and http://b.com!"))
        assertEquals(listOf(0 to 24), findUrlRanges("http://a.comhttp://b.com"))
    }
}
