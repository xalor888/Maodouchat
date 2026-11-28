package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownDetectionPolicyFuzzTest {

    private companion object {
        private const val ITERATIONS = 200
        private val WORDS = listOf("hello", "world", "foo", "bar", "text", "你好", "a", "b", "x1", "123", "a-b", "c_d")
        // 生产侧全部 ~xxx: 触发后缀（MarkdownDetectionPolicy.kt 实测抽出，wm 在源码里重复出现两次）。
        private val TILDE_SUFFIXES = listOf(
            "abbr", "an", "at", "az", "blur", "card", "ce", "cite", "cl", "code", "cp", "cr", "df", "dl",
            "draft", "ex", "file", "fs", "ft", "fw", "ga", "gf", "hz", "img", "inv", "ll", "loc", "lock",
            "ls", "lw", "mf", "mk", "note", "nudge", "nv", "oa", "ph", "pin", "pm", "pq", "ps", "pv",
            "qd", "qr", "rc", "rg", "rr", "rw", "rx", "sd", "sec", "sg", "sh", "sm", "sr", "ss", "st",
            "sum", "tip", "tk", "tp", "tr", "tx", "uf", "ur", "vc", "vd", "vf", "vid", "warn", "wm", "wp"
        )
        private val TRIGGERS = listOf(
            "# title", "## h2", "> quote", "- item", "* item", "```",
            "a **b** c", "a __b__ c", "a `code` b", "a ~~strike~~ b", "a ||b|| c",
            "[text](https://x.cn)", "![alt](https://x.cn/i.png)",
            "1. first", "12. second", "a | b |", "| a | b |",
            "- [ ] todo", "- [x] done", "- [X] done",
            "[a]: https://x.cn\n[^1]", "[^note]", "[[wiki]]",
            "a == b == c", "a ++ b ++ c", "a %% b %% c",
            "visit www.example.cn", "mailto:a@b.cn",
            "---", "***", "___", "text\n---",
            "Key: value\nother line", "~~sub:foo", "^sup:foo"
        ) + TILDE_SUFFIXES.map { "see ~$it:foo" }
    }

    private fun randomPlainDoc(random: Random): String {
        val lines = random.nextInt(0, 6)
        return List(lines) {
            List(random.nextInt(1, 5)) { WORDS.random(random) }.joinToString(" ")
        }.joinToString("\n")
    }

    private fun randomMixedDoc(random: Random): String {
        val fragments = WORDS + listOf(
            "#", "##", ">", "-", "*", "```", "**", "__", "`", "~~", "||", "[", "]", "(", ")",
            "|", ":", "=", "+", "%", "~", "^", "www.", "mailto:", "1.", "12.", "---", "***", "___",
            "[t](u)", "![a](u)", "[^x]", "[[w]]", "Key:", "http://x.cn"
        )
        val lines = random.nextInt(0, 7)
        return List(lines) {
            List(random.nextInt(1, 5)) { fragments.random(random) }.joinToString(" ")
        }.joinToString("\n")
    }

    @Test
    fun `random text never throws and is deterministic`() {
        val random = Random(0xD1_2026)
        repeat(ITERATIONS) { i ->
            val text = randomMixedDoc(random)
            val first = MarkdownDetectionPolicy.looksLikeMarkdown(text)
            val second = MarkdownDetectionPolicy.looksLikeMarkdown(text)
            assertEquals(first, second, "fuzz 迭代 #$i：同一输入两次判定必须相同")
        }
    }

    @Test
    fun `every trigger family is detected`() {
        TRIGGERS.forEach { trigger ->
            assertTrue(
                MarkdownDetectionPolicy.looksLikeMarkdown(trigger),
                "触发族应被判定为 Markdown：$trigger"
            )
        }
    }

    @Test
    fun `plain text is never detected`() {
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown(""))
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown("   "))
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown("plain text"))
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown("Key: value"))
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown("single == once"))
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown("single ++ once"))
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown("single %% once"))
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown("a | b"))
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown("3.14 is pi"))
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown("http://x.cn/path"))
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown("a * b"))
        val random = Random(0xD2_2026)
        repeat(ITERATIONS) { i ->
            val doc = randomPlainDoc(random)
            assertFalse(
                MarkdownDetectionPolicy.looksLikeMarkdown(doc),
                "fuzz 迭代 #$i：纯文本不得误判为 Markdown：$doc"
            )
        }
    }

    @Test
    fun `inserting a trigger into plain text always flips to true`() {
        val random = Random(0xD3_2026)
        repeat(ITERATIONS) { i ->
            val doc = randomPlainDoc(random)
            val trigger = TRIGGERS.random(random)
            val mixed = if (doc.isEmpty()) trigger else "$doc\n$trigger"
            assertTrue(
                MarkdownDetectionPolicy.looksLikeMarkdown(mixed),
                "fuzz 迭代 #$i：纯文本 + 触发片段必须判定为 Markdown：$mixed"
            )
            assertTrue(
                MarkdownDetectionPolicy.looksLikeMarkdown("$trigger\n$doc"),
                "fuzz 迭代 #$i：触发片段在前同样必须判定为 Markdown"
            )
        }
    }

    @Test
    fun `definition rule needs two lines and a colon with content after it`() {
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown("Key: value"))
        assertTrue(MarkdownDetectionPolicy.looksLikeMarkdown("Key: value\nother line"))
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown(": value\nother line"))
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown("Key: \nother line"))
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown("http://x.cn/a"))
        assertTrue(MarkdownDetectionPolicy.looksLikeMarkdown("http://x.cn/a\nKey: value"))
        assertFalse(MarkdownDetectionPolicy.looksLikeMarkdown("a".repeat(41) + ": v\nx"))
        assertTrue(MarkdownDetectionPolicy.looksLikeMarkdown("a".repeat(40) + ": v\nx"))
    }
}
