package com.maodouchat.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown 块解析契约测试。9.230 修复两处误解析后锁定行为：
 * 1. `>abc`（无空格）不再被吞首字符误渲染为引用；
 * 2. 代码围栏闭栏须反引号数恰等于开栏（CommonMark），更长反引号行不提前闭合。
 */
class MarkdownBlocksTest {

    @Test
    fun `heading levels parse`() {
        val blocks = parseMarkdownBlocks("# T1\n## T2\n### T3")
        assertEquals(MdBlock.Heading(1, "T1"), blocks[0])
        assertEquals(MdBlock.Heading(2, "T2"), blocks[1])
        assertEquals(MdBlock.Heading(3, "T3"), blocks[2])
    }

    @Test
    fun `quote requires space after gt`() {
        val quote = parseMarkdownBlocks("> hello")
        assertTrue(quote.single() is MdBlock.Quote)
        assertEquals("hello", (quote.single() as MdBlock.Quote).text)

        // 9.230：`>abc` 无空格不是引用，应保留为段落原文
        val notQuote = parseMarkdownBlocks(">3 表示大于 3")
        assertTrue(notQuote.single() is MdBlock.Paragraph)
        assertEquals(">3 表示大于 3", (notQuote.single() as MdBlock.Paragraph).text)
    }

    @Test
    fun `multi line quote joins and bare gt is blank line`() {
        val blocks = parseMarkdownBlocks("> a\n>\n> b")
        assertEquals(1, blocks.size)
        assertEquals("a\n\nb", (blocks.single() as MdBlock.Quote).text)
    }

    @Test
    fun `code fence closes only on exact backtick count`() {
        // 9.230：4 反引号开栏，内含更长的 5 反引号行不得提前闭合
        val src = "````\nval x = 1\n`````\nstill code\n````"
        val blocks = parseMarkdownBlocks(src)
        assertEquals(1, blocks.size)
        assertEquals("val x = 1\n`````\nstill code", (blocks.single() as MdBlock.Code).code)
    }

    @Test
    fun `fence with language tag inside stays content`() {
        // 9.230：```python 带语言标记不是闭栏（纯反引号行才是），整体仍在代码块内
        val src = "```\na\n```python\nb\n```"
        val blocks = parseMarkdownBlocks(src)
        assertEquals(1, blocks.size)
        assertEquals("a\n```python\nb", (blocks.single() as MdBlock.Code).code)
    }

    @Test
    fun `task list and plain list parse`() {
        val blocks = parseMarkdownBlocks("- [ ] todo\n- [x] done\n- item")
        assertEquals(MdBlock.ListItem("[ ]", "todo"), blocks[0])
        assertEquals(MdBlock.ListItem("[x]", "done"), blocks[1])
        assertEquals(MdBlock.ListItem("*", "item"), blocks[2])
    }

    @Test
    fun `ordered list keeps number`() {
        val blocks = parseMarkdownBlocks("1. first\n2. second")
        assertEquals(MdBlock.ListItem("1.", "first"), blocks[0])
        assertEquals(MdBlock.ListItem("2.", "second"), blocks[1])
    }

    @Test
    fun `table parses header separator and rows`() {
        // 表头行需 ≥2 个 | 才触发表格识别（与 looksLikeMarkdown 判定一致）
        val blocks = parseMarkdownBlocks("a | b | c\n--- | --- | ---\n1 | 2 | 3")
        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), table.rows)
    }

    @Test
    fun `hr variants`() {
        listOf("---", "***", "___").forEach { hr ->
            val blocks = parseMarkdownBlocks("text\n$hr")
            assertTrue(blocks.last() is MdBlock.Hr)
        }
    }

    @Test
    fun `crlf normalized and paragraph merges lines`() {
        val blocks = parseMarkdownBlocks("line1\r\nline2")
        assertEquals("line1\nline2", (blocks.single() as MdBlock.Paragraph).text)
    }

    @Test
    fun `looks like markdown basics`() {
        assertTrue(ChatMarkdown.looksLikeMarkdown("# title"))
        assertTrue(ChatMarkdown.looksLikeMarkdown("**bold**"))
        assertTrue(ChatMarkdown.looksLikeMarkdown("- item"))
        assertFalse(ChatMarkdown.looksLikeMarkdown("plain text"))
        assertFalse(ChatMarkdown.looksLikeMarkdown(""))
    }

    @Test
    fun `to plain text strips syntax`() {
        val md = "# Title\n**bold** and `code`\n[link](https://x.cn)"
        val plain = ChatMarkdown.toPlainText(md)
        assertFalse(plain.contains("**"))
        assertFalse(plain.contains("`"))
        assertTrue(plain.contains("bold"))
        assertTrue(plain.contains("link"))
        // 9.230：链接 URL 不得残留进复制/分享文本
        assertFalse(plain.contains("https://x.cn"))
    }

    @Test
    fun `to plain text strips image url`() {
        val plain = ChatMarkdown.toPlainText("see ![pic](https://img.cn/a.png) ok")
        assertFalse(plain.contains("https://img.cn"))
        assertFalse(plain.contains("!["))
        assertTrue(plain.contains("pic"))
    }
}
