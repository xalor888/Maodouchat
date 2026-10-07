package com.maodouchat.ui.component

/** Markdown 块级解析：围栏代码块 / 列表 / 段落。（行内扫描器见 MarkdownInlineParser） */

/** Pre-compiled, hot-path Markdown matchers — avoid recompiling Regex on every message render. */

internal val MD_ORDERED_LIST_REGEX = Regex("""^\d+\.\s+""")

internal fun parseMarkdownBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.replace("\r\n", "\n").lines()
    var i = 0
    val para = StringBuilder()
    fun flushPara() {
        if (para.isNotEmpty()) {
            out += MdBlock.Paragraph(para.toString().trimEnd())
            para.clear()
        }
    }
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("```") -> {
                flushPara()
                // 9.160：按开栏反引号长度匹配闭栏——4+ 反引号围栏（内含 ``` 行）此前
                // 在内层行提前闭合，后续代码被误解析为标题/引用/表格
                val fence = line.takeWhile { it == '`' }.length
                val buf = StringBuilder()
                i++
                // 9.230：CommonMark 规则——闭栏须纯反引号（可尾随空格）且数恰等于开栏；
                // 此前 startsWith 匹配，围栏内 ```python 行与更长反引号行都会提前闭合
                while (i < lines.size && !isClosingFence(lines[i], fence)) {
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(lines[i])
                    i++
                }
                out += MdBlock.Code(buf.toString())
            }
            line.startsWith("### ") -> {
                flushPara(); out += MdBlock.Heading(3, line.removePrefix("### ").trim())
            }
            line.startsWith("## ") -> {
                flushPara(); out += MdBlock.Heading(2, line.removePrefix("## ").trim())
            }
            line.startsWith("# ") -> {
                flushPara(); out += MdBlock.Heading(1, line.removePrefix("# ").trim())
            }
            line.trim() == "---" || line.trim() == "***" || line.trim() == "___" -> {
                flushPara(); out += MdBlock.Hr
            }
            // 9.230：仅 `> text` 与裸 `>` 算引用；`>abc`（无空格，如「>3 即大于 3」）
            // 此前被兜底 startsWith(">") 吞掉首字符误渲染为引用
            line.startsWith("> ") || line.trimEnd() == ">" -> {
                flushPara()
                val qbuf = StringBuilder()
                var j = i
                while (j < lines.size && (lines[j].startsWith("> ") || lines[j].trimEnd() == ">")) {
                    val qline = when {
                        lines[j].startsWith("> ") -> lines[j].removePrefix("> ")
                        else -> ""
                    }
                    if (qbuf.isNotEmpty()) qbuf.append('\n')
                    qbuf.append(qline.trimEnd())
                    j++
                }
                out += MdBlock.Quote(qbuf.toString().trim())
                i = j - 1
            }
            line.startsWith("- [ ] ") || line.startsWith("- [x] ") || line.startsWith("- [X] ") ||
                line.startsWith("+ [ ] ") || line.startsWith("+ [x] ") || line.startsWith("+ [X] ") ||
                line.startsWith("* [ ] ") || line.startsWith("* [x] ") || line.startsWith("* [X] ") -> {
                flushPara()
                val checked = line.contains("[x]", ignoreCase = true)
                val body = line.drop(6).trim()
                out += MdBlock.ListItem(if (checked) "[x]" else "[ ]", body)
            }
            line.contains("|") && line.count { it == '|' } >= 2 &&
                i + 1 < lines.size &&
                lines[i + 1].contains("|") &&
                lines[i + 1].replace("|", "").replace("-", "").replace(":", "").replace(" ", "").isEmpty() -> {
                flushPara()
                val rows = mutableListOf<List<String>>()
                fun splitRow(raw: String): List<String> =
                    raw.trim().trim('|').split("|").map { it.trim() }
                rows += splitRow(line)
                i += 2 // skip separator
                while (i < lines.size && lines[i].contains("|") && lines[i].count { it == '|' } >= 2) {
                    rows += splitRow(lines[i])
                    i++
                }
                out += MdBlock.Table(rows)
                continue
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                flushPara()
                out += MdBlock.ListItem("*", line.drop(2).trim())
            }
            MD_ORDERED_LIST_REGEX.containsMatchIn(line) -> {
                flushPara()
                val num = line.substringBefore('.').trim()
                out += MdBlock.ListItem("$num.", line.substringAfter('.').trim())
            }
            line.isBlank() -> flushPara()
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line)
            }
        }
        i++
    }
    flushPara()
    if (out.isEmpty()) out += MdBlock.Paragraph(src)
    return out
}

/** 9.230：闭栏判定——纯反引号（可尾随空格）且数量恰等于开栏；```python 属代码内容。 */
internal fun isClosingFence(line: String, fence: Int): Boolean {
    val trimmed = line.trimEnd()
    return trimmed.length == fence && trimmed.all { it == '`' }
}

