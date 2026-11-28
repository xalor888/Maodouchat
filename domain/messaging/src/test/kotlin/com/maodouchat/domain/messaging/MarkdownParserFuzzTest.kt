package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownParserFuzzTest {

    private companion object {
        private const val ITERATIONS = 200
        private val WORDS = listOf("hello", "你好", "world", "a", "b", "text", "x1", "foo", "bar", "123", "a-b", "c_d")
        private val BULLET_REGEX = Regex("""\*|\[ \]|\[x\]|\d+\.""")
    }

    private fun randomWord(random: Random): String = WORDS.random(random)

    private fun randomLine(random: Random): String {
        val w = randomWord(random)
        return when (random.nextInt(16)) {
            0 -> "# $w"
            1 -> "## $w"
            2 -> "### $w"
            3 -> "> $w"
            4 -> ">"
            5 -> "- $w"
            6 -> "* $w"
            7 -> "${random.nextInt(1, 100)}. $w"
            8 -> "- [ ] $w"
            9 -> "- [x] $w"
            10 -> "| $w | $w |"
            11 -> "|---|---|"
            12 -> "```"
            13 -> "---"
            14 -> w
            else -> ""
        }
    }

    private fun randomDoc(random: Random): String {
        val count = random.nextInt(0, 13)
        return List(count) { randomLine(random) }.joinToString("\n")
    }

    private fun randomCodeLine(random: Random): String {
        return when (random.nextInt(8)) {
            0 -> "let ${randomWord(random)} = ${random.nextInt(100)}"
            1 -> "${randomWord(random)} = ${randomWord(random)} + ${random.nextInt(10)}"
            2 -> "```${randomWord(random)}"
            3 -> "``"
            4 -> "````"
            5 -> "# ${randomWord(random)}"
            6 -> ""
            else -> randomWord(random)
        }
    }

    private fun isPureBacktickLine(line: String, fence: Int): Boolean {
        val trimmed = line.trimEnd()
        return trimmed.length == fence && trimmed.all { it == '`' }
    }

    @Test
    fun `random markdown never throws and parses deterministically`() {
        val random = Random(0xC1_2026)
        repeat(ITERATIONS) { i ->
            val src = randomDoc(random)
            val first = parseMarkdownBlocks(src)
            val second = parseMarkdownBlocks(src)
            assertEquals(first, second, "fuzz 迭代 #$i：同一输入两次解析必须相同")
            assertTrue(first.isNotEmpty(), "fuzz 迭代 #$i：解析结果不得为空")
        }
    }

    @Test
    fun `random markdown blocks satisfy structural invariants`() {
        val random = Random(0xC2_2026)
        repeat(ITERATIONS) { i ->
            val src = randomDoc(random)
            val blocks = parseMarkdownBlocks(src)
            val lines = src.replace("\r\n", "\n").lines()
            assertTrue(blocks.size <= maxOf(1, lines.size), "fuzz 迭代 #$i：块数不得超过行数")
            blocks.forEach { block ->
                when (block) {
                    is MdBlock.Heading -> assertTrue(block.level in 1..3, "fuzz 迭代 #$i：标题级别只应是 1..3")
                    is MdBlock.Paragraph -> assertTrue(
                        block.text.isNotEmpty() || src.isEmpty(),
                        "fuzz 迭代 #$i：空段落只应在空输入出现"
                    )
                    is MdBlock.ListItem -> assertTrue(
                        BULLET_REGEX.matches(block.bullet),
                        "fuzz 迭代 #$i：非法 bullet：${block.bullet}"
                    )
                    is MdBlock.Table -> {
                        assertTrue(block.rows.isNotEmpty(), "fuzz 迭代 #$i：表格至少一行")
                        block.rows.forEach { row -> assertTrue(row.isNotEmpty(), "fuzz 迭代 #$i：表格行不得空") }
                    }
                    else -> Unit
                }
            }
        }
    }

    @Test
    fun `fenced code blocks keep their body verbatim`() {
        val random = Random(0xC3_2026)
        repeat(ITERATIONS) { i ->
            val fence = random.nextInt(3, 6)
            val opener = "`".repeat(fence) + if (random.nextBoolean()) randomWord(random) else ""
            val body = List(random.nextInt(0, 6)) {
                var line = randomCodeLine(random)
                while (isPureBacktickLine(line, fence)) line = randomCodeLine(random)
                line
            }
            val terminated = random.nextBoolean()
            // 正文用 joinToString 拼，行尾不补换行：围栏循环会把末尾多出的空行
            // 也吃进代码内容（无闭栏时 "a\n" 与 "a" 的 Code 并不相同）。
            val src = buildString {
                append(opener)
                if (body.isNotEmpty()) {
                    append("\n")
                    append(body.joinToString("\n"))
                }
                if (terminated) {
                    append("\n")
                    append("`".repeat(fence))
                    append("\nafter")
                }
            }
            val blocks = parseMarkdownBlocks(src)
            // 围栏循环只在 buf 非空时才补 '\n'：打头的空行不产生分隔符，直接丢弃。
            val code = body.dropWhile { it.isEmpty() }.joinToString("\n")
            val expected = if (terminated) {
                listOf(MdBlock.Code(code), MdBlock.Paragraph("after"))
            } else {
                listOf(MdBlock.Code(code))
            }
            assertEquals(expected, blocks, "fuzz 迭代 #$i：围栏内容必须逐字保留")
        }
    }

    @Test
    fun `isClosingFence matches its specification`() {
        val random = Random(0xC4_2026)
        repeat(ITERATIONS) { i ->
            val len = random.nextInt(0, 9)
            val line = buildString {
                repeat(len) {
                    append(
                        when (random.nextInt(4)) {
                            0 -> '`'
                            1 -> ' '
                            2 -> '\t'
                            else -> 'x'
                        }
                    )
                }
            }
            val fence = random.nextInt(0, 7)
            val trimmed = line.trimEnd()
            val expected = trimmed.length == fence && trimmed.all { it == '`' }
            assertEquals(expected, isClosingFence(line, fence), "fuzz 迭代 #$i：行=[$line] 围栏=$fence")
        }
    }

    @Test
    fun `random tables parse header and rows`() {
        val random = Random(0xC5_2026)
        repeat(ITERATIONS) { i ->
            val cols = random.nextInt(2, 5)
            val header = List(cols) { randomWord(random) }
            val separator = header.joinToString("|", "|", "|") {
                when (random.nextInt(3)) {
                    0 -> "---"
                    1 -> ":---"
                    else -> "---:"
                }
            }
            val rows = List(random.nextInt(0, 5)) { List(cols) { randomWord(random) } }
            val src = buildString {
                append("| ").append(header.joinToString(" | ")).append(" |\n")
                append(separator).append("\n")
                rows.forEach { row -> append("| ").append(row.joinToString(" | ")).append(" |\n") }
                append("tail")
            }
            val blocks = parseMarkdownBlocks(src)
            assertEquals(
                listOf(MdBlock.Table(listOf(header) + rows), MdBlock.Paragraph("tail")),
                blocks,
                "fuzz 迭代 #$i：表格头行与数据行必须原样解析"
            )
        }
        // 固定钉住：缺分隔行时不成表，整段落入段落。
        assertEquals(
            listOf(MdBlock.Paragraph("| a | b |\nnot a separator")),
            parseMarkdownBlocks("| a | b |\nnot a separator")
        )
    }
}
