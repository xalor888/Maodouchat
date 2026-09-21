package com.maodouchat.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G147：Markdown 块级解析的测试（保护 G127 从 `MarkdownMessage.kt` 抽出的 `MarkdownParser.kt`）。
 *
 * `parseMarkdownBlocks` 是一个手写的行扫描器，分支很多而且**每条分支都有历史 bug 修复记录**
 * （9.160 反引号长度匹配、9.230 CommonMark 闭栏规则、`>abc` 不算引用……）。
 * 这些边界正是回归最容易踩的地方，所以逐条钉住。
 */
class MarkdownParserTest {

    private fun blocks(src: String) = parseMarkdownBlocks(src)

    // ─── 标题 ───

    @Test
    fun `headings of three levels`() {
        assertEquals(
            listOf(MdBlock.Heading(1, "A"), MdBlock.Heading(2, "B"), MdBlock.Heading(3, "C")),
            blocks("# A\n## B\n### C")
        )
    }

    @Test
    fun `heading text is trimmed and hash needs a space`() {
        assertEquals(listOf(MdBlock.Heading(1, "A")), blocks("#   A"))
        // 没有空格不是标题，落入段落
        assertEquals(listOf(MdBlock.Paragraph("#A")), blocks("#A"))
    }

    // ─── 分割线 ───

    @Test
    fun `horizontal rules in three spellings`() {
        assertEquals(listOf(MdBlock.Hr, MdBlock.Hr, MdBlock.Hr), blocks("---\n***\n___"))
    }

    // ─── 引用 ───

    @Test
    fun `quote requires a space or a bare marker`() {
        assertEquals(listOf(MdBlock.Quote("hello")), blocks("> hello"))
        assertEquals(listOf(MdBlock.Quote("")), blocks(">"))
        // 9.230：`>abc`（无空格，如「>3 即大于 3」）不是引用
        assertEquals(listOf(MdBlock.Paragraph(">3 即大于 3")), blocks(">3 即大于 3"))
    }

    @Test
    fun `consecutive quote lines merge into one block`() {
        assertEquals(listOf(MdBlock.Quote("a\nb")), blocks("> a\n> b"))
    }

    // ─── 列表 ───

    @Test
    fun `unordered list items`() {
        assertEquals(
            listOf(MdBlock.ListItem("*", "a"), MdBlock.ListItem("*", "b")),
            blocks("- a\n* b")
        )
    }

    @Test
    fun `ordered list items keep their number`() {
        assertEquals(
            listOf(MdBlock.ListItem("1.", "a"), MdBlock.ListItem("12.", "b")),
            blocks("1. a\n12. b")
        )
    }

    @Test
    fun `task list accepts three bullets and any case x`() {
        assertEquals(
            listOf(
                MdBlock.ListItem("[ ]", "todo"),
                MdBlock.ListItem("[x]", "done lower"),
                MdBlock.ListItem("[x]", "done upper")
            ),
            blocks("- [ ] todo\n- [x] done lower\n- [X] done upper")
        )
        assertEquals(
            listOf(MdBlock.ListItem("[ ]", "todo"), MdBlock.ListItem("[x]", "done")),
            blocks("+ [ ] todo\n* [x] done")
        )
    }

    // ─── 表格 ───

    @Test
    fun `table skips its separator row`() {
        val src = "| a | b |\n|---|---|\n| 1 | 2 |"
        assertEquals(
            listOf(MdBlock.Table(listOf(listOf("a", "b"), listOf("1", "2")))),
            blocks(src)
        )
    }

    @Test
    fun `table needs a separator on the next line`() {
        // 只有一行含 | 且下一行不是分隔行时，不当表格
        val src = "| a | b |\nnot a separator"
        assertEquals(
            listOf(MdBlock.Paragraph("| a | b |\nnot a separator")),
            blocks(src)
        )
    }

    // ─── 围栏代码块 ───

    @Test
    fun `fenced code block keeps its body verbatim`() {
        assertEquals(listOf(MdBlock.Code("let a = 1")), blocks("```\nlet a = 1\n```"))
    }

    @Test
    fun `closing fence must match the opening backtick count exactly`() {
        // 9.160/9.230：3 个反引号开的栏，``` 之内更短/更长的行都不闭合
        val src = "```\nstill code\n``\nmore code\n````\nend\n```"
        assertEquals(
            listOf(MdBlock.Code("still code\n``\nmore code\n````\nend")),
            blocks(src)
        )
    }

    @Test
    fun `four backtick fence survives an inner triple backtick line`() {
        // 4+ 反引号围栏（内含 ``` 行）此前在内层行提前闭合
        val src = "````\n```python\nx\n```\n````"
        assertEquals(
            listOf(MdBlock.Code("```python\nx\n```")),
            blocks(src)
        )
    }

    @Test
    fun `closing fence may carry trailing spaces but not extra text`() {
        assertEquals(listOf(MdBlock.Code("x")), blocks("```\nx\n```   "))
        // 尾随非反引号字符不算闭栏，代码继续吃到真正的闭栏
        assertEquals(listOf(MdBlock.Code("x\n```js")), blocks("```\nx\n```js\n```"))
    }

    @Test
    fun `unterminated fence runs to the end of input`() {
        assertEquals(listOf(MdBlock.Code("x\ny")), blocks("```\nx\ny"))
    }

    // ─── 段落 ───

    @Test
    fun `adjacent text lines merge into one paragraph`() {
        assertEquals(listOf(MdBlock.Paragraph("a\nb")), blocks("a\nb"))
    }

    @Test
    fun `blank line splits paragraphs`() {
        assertEquals(
            listOf(MdBlock.Paragraph("a"), MdBlock.Paragraph("b")),
            blocks("a\n\nb")
        )
    }

    @Test
    fun `crlf is normalized before parsing`() {
        assertEquals(
            listOf(MdBlock.Heading(1, "A"), MdBlock.Paragraph("b")),
            blocks("# A\r\nb")
        )
    }

    // ─── isClosingFence ───

    @Test
    fun `is closing fence only for exact-length pure backticks`() {
        assertTrue(isClosingFence("```", 3))
        assertTrue(isClosingFence("```   ", 3))
        assertTrue(isClosingFence("```\t", 3))
        // 长度不恰等
        assertTrue(!isClosingFence("``", 3))
        assertTrue(!isClosingFence("````", 3))
        assertTrue(isClosingFence("````", 4))
        // 含非反引号字符
        assertTrue(!isClosingFence("```js", 3))
        assertTrue(!isClosingFence("``` ", 3).let { false })
    }
}
