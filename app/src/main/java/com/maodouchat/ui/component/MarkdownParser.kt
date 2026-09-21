package com.maodouchat.ui.component

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * 聊天消息的 Markdown 解析（G127 从 `MarkdownMessage.kt` 拆出，原 938 行）。
 *
 * 三层：
 * 1. `parseMarkdownBlocks` —— 块级：围栏代码块 / 有序列表 / 无序列表 / 段落；
 * 2. `isClosingFence` —— 代码围栏的闭合判定；
 * 3. `inlineMarkdown` —— 行内：`**粗**` `*斜*` `~~删除~~` `` `代码` `` `[标签](url)` `![图](url)`
 *    以及本项目的自定义前缀 `~cl:` `~hz:` `~an:`（彩色/横着/按对齐）。
 *
 * `inlineMarkdown` 是**手写的字符扫描器**（`while (i < s.length) { when { ... } }`），
 * 不是正则——因为行内语法互相嵌套且允许不闭合，正则表达不了。
 * 它 816 行，是本文件的大头；本轮只做搬移，**没有拆它的函数体**。
 *
 * **拆解约束**：不抓任何全局单例、不读数据库、不 import `MaodouchatApp`。
 * 纯搬移，不改判断。
 */

/** Pre-compiled, hot-path Markdown matchers — avoid recompiling Regex on every message render. */
internal val MD_LINK_REGEX = Regex("""\[[^\]]+\]\([^\)]+\)""")

internal val MD_IMAGE_REGEX = Regex("""!\[[^\]]*\]\([^\)]+\)""")

internal val MD_ORDERED_LIST_REGEX = Regex("""^\d+\.\s+""")

internal val MD_ORDERED_LIST_ML_REGEX = Regex("""^\d+\.\s""", RegexOption.MULTILINE)

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

internal fun inlineMarkdown(text: String, baseColor: Color, onLinkClick: (String) -> Unit = {}) = buildAnnotatedString {
    // Patterns: **bold**, *italic*, ~~strike~~, `code`, [label](url), ||spoiler||, __underline__, ==mark==, bare URLs
    var i = 0
    val s = text
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) {
                        append(s.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(s[i]); i++
                }
            }
            // 9.160：~~ 删除线分支放行 ~~sub:/~~cite:（此前先命中删除线，
            // 下标语法 ~~sub:…~~ 恒被渲染成删除线，460 分支为死代码）
            s.startsWith("~~", i) && !s.startsWith("~~sub:", i) && !s.startsWith("~~cite:", i) -> {
                val end = s.indexOf("~~", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = baseColor)) {
                        append(s.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(s[i]); i++
                }
            }
            // 9.160：双波浪引用（与单波浪 ~cite: 同款样式）
            s.startsWith("~~cite:", i) -> {
                val end = s.indexOf("~~", i + 7)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor.copy(alpha = 0.85f))) {
                        append(s.substring(i + 7, end))
                    }
                    i = end + 2
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("||", i) -> {
                val end = s.indexOf("||", i + 2)
                if (end > i) {
                    withStyle(
                        SpanStyle(
                            color = baseColor.copy(alpha = 0.02f),
                            background = baseColor.copy(alpha = 0.55f),
                            fontWeight = FontWeight.Medium
                        )
                    ) {
                        append(s.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("==", i) -> {
                val end = s.indexOf("==", i + 2)
                if (end > i) {
                    withStyle(
                        SpanStyle(
                            background = baseColor.copy(alpha = 0.18f),
                            color = baseColor,
                            fontWeight = FontWeight.Medium
                        )
                    ) {
                        append(s.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("++", i) -> {
                val end = s.indexOf("++", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = baseColor)) {
                        append(s.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~~sub:", i) -> {
                val end = s.indexOf("~~", i + 6)
                if (end > i) {
                    withStyle(SpanStyle(fontSize = 11.sp, color = baseColor.copy(alpha = 0.9f), baselineShift = BaselineShift(-0.25f))) {
                        append(s.substring(i + 6, end))
                    }
                    i = end + 2
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("^sup:", i) -> {
                val end = s.indexOf("^", i + 5)
                if (end > i) {
                    withStyle(SpanStyle(fontSize = 11.sp, color = baseColor.copy(alpha = 0.9f), baselineShift = BaselineShift(0.35f))) {
                        append(s.substring(i + 5, end))
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("[[", i) -> {
                val end = s.indexOf("]]", i + 2)
                if (end > i) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            background = baseColor.copy(alpha = 0.12f),
                            color = baseColor
                        )
                    ) {
                        append(s.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("%%", i) -> {
                val end = s.indexOf("%%", i + 2)
                if (end > i) {
                    withStyle(
                        SpanStyle(
                            background = baseColor.copy(alpha = 0.16f),
                            color = baseColor,
                            fontWeight = FontWeight.Medium
                        )
                    ) {
                        append(s.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~pin:", i) -> {
                val end = s.indexOf('~', i + 5)
                if (end > i + 5) {
                    val body = s.substring(i + 5, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline, color = baseColor)) {
                        append(body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~at:", i) -> {
                val end = s.indexOf('~', i + 4)
                if (end > i + 4) {
                    val body = s.substring(i + 4, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF4FC3F7))) {
                        append("@" + body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~inv:", i) -> {
                val end = s.indexOf('~', i + 5)
                if (end > i + 5) {
                    val body = s.substring(i + 5, end)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Color(0xFFAED581))) {
                        append(body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~nudge:", i) -> {
                val end = s.indexOf('~', i + 7)
                if (end > i + 7) {
                    val body = s.substring(i + 7, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFFFFAB91))) {
                        append(body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~code:", i) -> {
                val end = s.indexOf('~', i + 6)
                if (end > i + 6) {
                    val body = s.substring(i + 6, end)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Color(0xFF80DEEA))) {
                        append(body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~qr:", i) -> {
                val end = s.indexOf('~', i + 4)
                if (end > i + 4) {
                    val body = s.substring(i + 4, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF81D4FA))) {
                        append(body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~card:", i) -> {
                val end = s.indexOf('~', i + 6)
                if (end > i + 6) {
                    val body = s.substring(i + 6, end)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Color(0xFFCE93D8))) {
                        append(body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~blur:", i) -> {
                val end = s.indexOf('~', i + 6)
                if (end > i + 6) {
                    val body = s.substring(i + 6, end)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFFB0BEC5))) {
                        append(body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~dl:", i) -> {
                val end = s.indexOf('~', i + 4)
                if (end > i + 4) {
                    val body = s.substring(i + 4, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = Color(0xFF90CAF9))) {
                        append(body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~loc:", i) -> {
                val end = s.indexOf('~', i + 5)
                if (end > i + 5) {
                    val body = s.substring(i + 5, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFFA5D6A7))) { append(body) }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            s.startsWith("~file:", i) -> {
                val end = s.indexOf('~', i + 6)
                if (end > i + 6) {
                    val body = s.substring(i + 6, end)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Color(0xFFFFCC80))) { append(body) }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            s.startsWith("~sec:", i) -> {
                val end = s.indexOf('~', i + 5)
                if (end > i + 5) {
                    val body = s.substring(i + 5, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFEF9A9A))) { append(body) }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            s.startsWith("~wm:", i) -> {
                val end = s.indexOf('~', i + 4)
                if (end > i + 4) {
                    val body = s.substring(i + 4, end)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFFB39DDB))) { append(body) }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            s.startsWith("~img:", i) -> {
                val end = s.indexOf('~', i + 5)
                if (end > i + 5) {
                    val body = s.substring(i + 5, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF80CBC4))) { append(body) }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            s.startsWith("~vid:", i) -> {
                val end = s.indexOf('~', i + 5)
                if (end > i + 5) {
                    val body = s.substring(i + 5, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFFEF9A9A))) { append(body) }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            s.startsWith("~sum:", i) -> {
                val end = s.indexOf('~', i + 5)
                if (end > i + 5) {
                    val body = s.substring(i + 5, end)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF9FA8DA))) { append(body) }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            s.startsWith("~df:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF6A1B9A))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~sm:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF00695C))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~gf:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFFE65100))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            // 9.146：删除不可达的重复 ~wm: 分支（649 行首分支恒命中，此分支为死代码）
            s.startsWith("~vc:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF1565C0))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~vd:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFFAD1457))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~wp:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF00897B))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~fs:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF5D4037))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~ur:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFFC62828))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~rg:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF6A1B9A))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~sd:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF0277BD))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~pv:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF546E7A))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~ph:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~tk:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFFEF6C00))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~qd:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF37474F))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~oa:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF00695C))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~cl:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF00838F))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~hz:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF6A1B9A))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~an:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF283593))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~nv:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF00838F))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~sh:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFFC62828))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~cp:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF6A1B9A))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~ex:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFFAD1457))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~lw:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFB71C1C))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~fw:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF1565C0))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~ce:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~vf:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFF4A148C))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~ss:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF00695C))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~pq:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF283593))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~cr:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFF37474F))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~mk:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF5D4037))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~ft:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF00695C))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~sr:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF4527A0))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~ll:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF0277BD))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~pm:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF546E7A))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~uf:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFFBF360C))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~rx:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF6A1B9A))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~st:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFFF9A825))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~mf:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFF37474F))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~tp:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF00838F))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~rr:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFFAD1457))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~ps:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF6A1B9A))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~ls:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF5D4037))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~rc:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF455A64))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
                        s.startsWith("~az:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF3949AB))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~ga:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF00838F))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
                        s.startsWith("~sg:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF00897B))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~tx:", i) -> {
                val end = s.indexOf('~', i + 4).let { if (it < 0) s.length else it }
                val body = s.substring(i + 4, end)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color(0xFF5E35B1))) { append(body) }
                i = if (end < s.length && s[end] == '~') end + 1 else end
            }
            s.startsWith("~rw:", i) -> {
                val end = s.indexOf('~', i + 4)
                if (end > i + 4) {
                    val body = s.substring(i + 4, end)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = Color(0xFFFFE082))) { append(body) }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            s.startsWith("~draft:", i) -> {
                val end = s.indexOf('~', i + 7)
                if (end > i + 7) {
                    val body = s.substring(i + 7, end)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor.copy(alpha = 0.88f))) {
                        append(body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~tr:", i) -> {
                val end = s.indexOf('~', i + 4)
                if (end > i + 4) {
                    val body = s.substring(i + 4, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = Color(0xFF64B5F6))) {
                        append(body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~poll:", i) -> {
                val end = s.indexOf('~', i + 6)
                if (end > i + 6) {
                    val body = s.substring(i + 6, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFF81C784))) {
                        append(body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~app:", i) -> {
                val end = s.indexOf('~', i + 5)
                if (end > i + 5) {
                    val body = s.substring(i + 5, end)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = baseColor.copy(alpha = 0.9f))) {
                        append(body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~lock:", i) -> {
                val end = s.indexOf('~', i + 6)
                if (end > i + 6) {
                    val body = s.substring(i + 6, end)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, color = baseColor.copy(alpha = 0.92f))) {
                        append(body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~warn:", i) -> {
                val end = s.indexOf('~', i + 6)
                if (end > i + 6) {
                    val body = s.substring(i + 6, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFFFFB74D))) {
                        append(body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~tip:", i) -> {
                val end = s.indexOf('~', i + 5)
                if (end > i + 5) {
                    val body = s.substring(i + 5, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = baseColor.copy(alpha = 0.9f))) {
                        append(body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~note:", i) -> {
                val end = s.indexOf('~', i + 6)
                if (end > i + 6) {
                    val body = s.substring(i + 6, end)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor.copy(alpha = 0.85f))) {
                        append(body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~abbr:", i) -> {
                val end = s.indexOf('~', i + 6)
                if (end > i + 6) {
                    val body = s.substring(i + 6, end)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = baseColor.copy(alpha = 0.9f))) {
                        append(body)
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("~cite:", i) -> {
                val end = s.indexOf("~", i + 6)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor.copy(alpha = 0.85f))) {
                        append(s.substring(i + 6, end))
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("__", i) -> {
                val end = s.indexOf("__", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = baseColor)) {
                        append(s.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("`", i) -> {
                val end = s.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color.Black.copy(alpha = 0.12f),
                            color = baseColor
                        )
                    ) {
                        append(s.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("[", i) -> {
                val close = s.indexOf("](", i)
                val end = if (close > i) s.indexOf(')', close + 2) else -1
                if (close > i && end > close) {
                    val label = s.substring(i + 1, close)
                    val linkUrl = s.substring(close + 2, end)
                    withLink(
                        androidx.compose.ui.text.LinkAnnotation.Clickable(
                            tag = linkUrl,
                            linkInteractionListener = androidx.compose.ui.text.LinkInteractionListener { onLinkClick(linkUrl) }
                        )
                    ) {
                        withStyle(
                            SpanStyle(
                                color = Color(0xFF64B5F6),
                                textDecoration = TextDecoration.Underline,
                                fontWeight = FontWeight.Medium
                            )
                        ) {
                            append(label)
                        }
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            s.startsWith("*", i) && !s.startsWith("**", i) -> {
                val end = s.indexOf('*', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor)) {
                        append(s.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(s[i]); i++
                }
            }
            else -> {
                if (s.startsWith("http://", i) || s.startsWith("https://", i)) {
                    var endUrl = i
                    while (endUrl < s.length && !s[endUrl].isWhitespace() && s[endUrl] !in setOf('<', '>', '"', '\'')) {
                        endUrl++
                    }
                    while (endUrl > i && s[endUrl - 1] in setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')) {
                        endUrl--
                    }
                    val url = s.substring(i, endUrl)
                    withLink(
                        androidx.compose.ui.text.LinkAnnotation.Clickable(
                            tag = url,
                            linkInteractionListener = androidx.compose.ui.text.LinkInteractionListener { onLinkClick(url) }
                        )
                    ) {
                        withStyle(
                            SpanStyle(
                                color = baseColor.copy(alpha = 0.95f),
                                textDecoration = TextDecoration.Underline
                            )
                        ) {
                            append(url)
                        }
                    }
                    i = endUrl
                } else {
                    append(s[i]); i++
                }
            }
        }
    }
}
