package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatMarkdownToPlainTextFuzzTest {

    private companion object {
        private const val ITERATIONS = 200
        private val WORDS = listOf("hello", "world", "foo", "bar", "text", "你好", "a", "b", "x1", "123", "a-b", "c_d")
        private val FRAGMENTS = listOf(
            "# T", "## T", "> q", "- i", "* i", "1. i", "```",
            "**b**", "__b__", "~~s~~", "`c`", "[t](https://u.cn)",
            "![a](https://u.cn/i.png)", "plain", "hello world"
        )
    }

    private fun randomPlainDoc(random: Random): String {
        val lines = random.nextInt(0, 6)
        return List(lines) {
            List(random.nextInt(1, 5)) { WORDS.random(random) }.joinToString(" ")
        }.joinToString("\n")
    }

    private fun randomMixedDoc(random: Random): String {
        val lines = random.nextInt(0, 7)
        return List(lines) { FRAGMENTS.random(random) }.joinToString("\n")
    }

    private fun isSubsequence(sub: String, full: String): Boolean {
        var index = 0
        for (ch in sub) {
            val found = full.indexOf(ch, index)
            if (found < 0) return false
            index = found + 1
        }
        return true
    }

    @Test
    fun `random markdown never throws and is deterministic`() {
        val random = Random(0xD4_2026)
        repeat(ITERATIONS) { i ->
            val doc = randomMixedDoc(random)
            val first = ChatMarkdown.toPlainText(doc)
            val second = ChatMarkdown.toPlainText(doc)
            assertEquals(first, second, "fuzz 迭代 #$i：同一输入两次转换必须相同")
        }
    }

    @Test
    fun `output is a subsequence of input never longer and always trimmed`() {
        val random = Random(0xD5_2026)
        repeat(ITERATIONS) { i ->
            val doc = randomMixedDoc(random)
            val plain = ChatMarkdown.toPlainText(doc)
            assertTrue(plain.length <= doc.length, "fuzz 迭代 #$i：纯文本不得比原文长：$doc")
            assertEquals(plain.trim(), plain, "fuzz 迭代 #$i：输出首尾不得留空白：$plain")
            assertTrue(isSubsequence(plain, doc), "fuzz 迭代 #$i：输出只能删字符、不得引入新字符：$doc -> $plain")
        }
    }

    @Test
    fun `plain text passes through unchanged modulo trim`() {
        val random = Random(0xD6_2026)
        repeat(ITERATIONS) { i ->
            val doc = randomPlainDoc(random)
            assertEquals(doc.trim(), ChatMarkdown.toPlainText(doc), "fuzz 迭代 #$i：纯文本应原样透出：$doc")
        }
    }

    @Test
    fun `inline syntax strips to its text and urls never leak`() {
        assertEquals("t", ChatMarkdown.toPlainText("[t](https://x.cn)"))
        assertEquals("see pic ok", ChatMarkdown.toPlainText("see ![pic](https://img.cn/a.png) ok"))
        assertEquals("bold", ChatMarkdown.toPlainText("**bold**"))
        assertEquals("bold", ChatMarkdown.toPlainText("__bold__"))
        assertEquals("strike", ChatMarkdown.toPlainText("~~strike~~"))
        assertEquals("code", ChatMarkdown.toPlainText("`code`"))
        assertEquals("Title", ChatMarkdown.toPlainText("# Title"))
        assertEquals("Sub", ChatMarkdown.toPlainText("## Sub"))
        assertEquals("quote", ChatMarkdown.toPlainText("> quote"))
        assertEquals("item", ChatMarkdown.toPlainText("- item"))
        assertEquals("item", ChatMarkdown.toPlainText("* item"))
        assertEquals("first", ChatMarkdown.toPlainText("1. first"))
        assertEquals("second", ChatMarkdown.toPlainText("12. second"))
        assertEquals(
            "Title\nbold and code\nlink",
            ChatMarkdown.toPlainText("# Title\n**bold** and `code`\n[link](https://x.cn)")
        )
        val random = Random(0xD7_2026)
        repeat(ITERATIONS) { i ->
            val word = WORDS.random(random)
            val doc = "[$word](https://example.cn/${random.nextInt(1000)}) and ![img](https://img.cn/${random.nextInt(1000)}.png)"
            val plain = ChatMarkdown.toPlainText(doc)
            assertFalse(plain.contains("https://"), "fuzz 迭代 #$i：链接 URL 不得残留进纯文本：$doc -> $plain")
            assertTrue(plain.contains(word), "fuzz 迭代 #$i：链接文本应保留：$doc -> $plain")
        }
    }

    @Test
    fun `fence quirk is pinned`() {
        // 行内代码正则先于行首规则执行，会把成对围栏咬掉一枚反引号、只剩两枚（生产行为原样钉住）。
        assertEquals("``kotlin\ncode\n``", ChatMarkdown.toPlainText("```kotlin\ncode\n```"))
        assertEquals("``\ncode\n``", ChatMarkdown.toPlainText("```\ncode\n```"))
        // 无闭栏的围栏行则由行首规则整行剥掉。
        assertEquals("text\n\ncode", ChatMarkdown.toPlainText("text\n```\ncode"))
    }
}
