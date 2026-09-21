package com.maodouchat.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.LocalSentBubbleContent
import com.maodouchat.ui.theme.TextWhite


/**
 * Lightweight chat Markdown subset:
 * headings, bold, italic, strike, inline code, fenced code, links, lists, quotes.
 * No HTML. Safe for E2EE plaintext after local decrypt.
 */
object ChatMarkdown {
    /** 1.18：剥离名片标记行 `[contactUser:xxx]`（含前一换行），会话列表预览等不显示裸标记。 */
    private val CONTACT_CARD_MARKER_RE = Regex("\\n?\\[contactUser:[^\\]]+\\]")
    fun stripContactCardMarker(text: String): String = CONTACT_CARD_MARKER_RE.replace(text, "")

    fun looksLikeMarkdown(text: String): Boolean {
        if (text.isBlank()) return false
        val lines = text.lines()
        if (lines.any { it.startsWith("#") || it.startsWith("> ") || it.startsWith("- ") || it.startsWith("* ") || it.startsWith("```") }) return true
        if (text.contains("**") || text.contains("__") || text.contains("`") || text.contains("~~") || text.contains("||")) return true
        if (MD_LINK_REGEX.containsMatchIn(text)) return true
        if (MD_ORDERED_LIST_ML_REGEX.containsMatchIn(text)) return true
        // GFM-ish table rows: a|b with a separator line of dashes
        if (lines.any { line -> line.contains("|") && line.count { it == '|' } >= 2 }) return true
        // Task list / footnotes / images
        if (lines.any {
                val t = it.trimStart()
                t.startsWith("- [ ]") || t.startsWith("- [x]") || t.startsWith("- [X]")
            }) return true
        if (text.contains("]:") && text.contains("[^")) return true
        if (MD_IMAGE_REGEX.containsMatchIn(text)) return true
        if (text.contains("==") && text.indexOf("==") != text.lastIndexOf("==")) return true
        if (text.contains("++") && text.indexOf("++") != text.lastIndexOf("++")) return true
        if (text.contains("~~sub:") || text.contains("^sup:")) return true
        if (text.contains("[[") && text.contains("]]")) return true
        if (text.contains("[^") && text.contains("]")) return true
        if (text.contains("www.") || text.contains("mailto:")) return true
        if (text.contains("%%") && text.indexOf("%%") != text.lastIndexOf("%%")) return true
        if (text.contains("~cite:") || text.contains("~~cite:")) return true
        if (text.contains("~abbr:")) return true
        if (text.contains("~note:")) return true
        if (text.contains("~tip:")) return true
        if (text.contains("~warn:")) return true
        if (text.contains("~lock:")) return true
        if (text.contains("~pin:")) return true
        if (text.contains("~draft:")) return true
        if (text.contains("~at:")) return true
        if (text.contains("~inv:")) return true
        if (text.contains("~nudge:")) return true
        if (text.contains("~code:")) return true
        if (text.contains("~qr:")) return true
        if (text.contains("~card:")) return true
        if (text.contains("~blur:")) return true
        if (text.contains("~dl:")) return true
        if (text.contains("~loc:")) return true
        if (text.contains("~file:")) return true
        if (text.contains("~sec:")) return true
        if (text.contains("~wm:")) return true
        if (text.contains("~vc:")) return true
        if (text.contains("~vd:")) return true
        if (text.contains("~wp:")) return true
        if (text.contains("~fs:")) return true
        if (text.contains("~ur:")) return true
        if (text.contains("~rg:")) return true
        if (text.contains("~sd:")) return true
        if (text.contains("~pv:")) return true
        if (text.contains("~ph:")) return true
        if (text.contains("~tk:")) return true
        if (text.contains("~qd:")) return true
        if (text.contains("~oa:")) return true
        if (text.contains("~cl:")) return true
        if (text.contains("~hz:")) return true
        if (text.contains("~an:")) return true
        if (text.contains("~nv:")) return true
        if (text.contains("~sh:")) return true
        if (text.contains("~cp:")) return true
        if (text.contains("~ex:")) return true
        if (text.contains("~lw:")) return true
        if (text.contains("~fw:")) return true
        if (text.contains("~ce:")) return true
        if (text.contains("~vf:")) return true
        if (text.contains("~ss:")) return true
        if (text.contains("~pq:")) return true
        if (text.contains("~cr:")) return true
        if (text.contains("~mk:")) return true
        if (text.contains("~ft:")) return true
        if (text.contains("~sr:")) return true
        if (text.contains("~ll:")) return true
        if (text.contains("~pm:")) return true
        if (text.contains("~uf:")) return true
        if (text.contains("~rx:")) return true
        if (text.contains("~st:")) return true
        if (text.contains("~mf:")) return true
        if (text.contains("~tp:")) return true
        if (text.contains("~rr:")) return true
        if (text.contains("~ps:")) return true
        if (text.contains("~ls:")) return true
        if (text.contains("~rc:")) return true
        if (text.contains("~img:")) return true
        if (text.contains("~vid:")) return true
        if (text.contains("~sum:")) return true
        if (text.contains("~rw:")) return true
        if (text.contains("~sg:")) return true
        if (text.contains("~tx:")) return true
        if (text.contains("~az:")) return true
        if (text.contains("~ga:")) return true
        if (text.contains("~df:")) return true
        if (text.contains("~sm:")) return true
        if (text.contains("~gf:")) return true
        if (text.contains("~wm:")) return true
        if (text.contains("~tr:")) return true
        if (lines.any { val t = it.trim(); t == "---" || t == "***" || t == "___" }) return true
        // Definition-style lines: Term: definition
        if (lines.any { line ->
            val t = line.trim()
            ':' in t && !t.startsWith("http") && t.indexOf(':') in 1..40 && t.substringAfter(':').isNotBlank()
        } && lines.size >= 2) return true
        return false
    }

    /** 0.71：剥离常见 Markdown 语法得到纯文本（「复制为纯文本」用）。 */
    fun toPlainText(markdown: String): String {
        var text = markdown
        // [t](url) -> t（9.230：必须先于行首语法剥离——否则 `- [t](url)` 的 `- ` 前缀
        // 被先行删去后无碍，但图片 ![alt](url) 的 ! 残留及顺序交换引发的链接断裂已规避）
        text = text.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        text = text.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
        // 9.230：内联语法必须先于行首剥离——行首规则中的 ```+ 会吞掉行内代码的
        // 首个反引号，导致孤儿反引号残留进复制/分享文本
        text = text.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        text = text.replace(Regex("__(.+?)__"), "$1")
        text = text.replace(Regex("~~(.+?)~~"), "$1")
        text = text.replace(Regex("`([^`]+)`"), "$1")
        // 行首语法：标题/引用/无序/有序列表/代码围栏
        text = text.replace(Regex("(?m)^\\s{0,3}(#{1,6}[ \\t]+|> ?|[-+*] ?|\\d+\\. ?|```+)"), "")
        return text.trim()
    }
}

@Composable
fun MarkdownMessageContent(
    text: String,
    isOwnMessage: Boolean,
    modifier: Modifier = Modifier,
    /** 点击消息内 URL 时回调（scheme 白名单与密聊外链拦截由调用方负责）。 */
    onLinkClick: (String) -> Unit = {},
    allowSelection: Boolean = true
) {
    val bodyColor = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
    val codeBg = if (isOwnMessage) LocalSentBubbleContent.current.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f)
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                                is MdBlock.Hr -> {
                    androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        color = bodyColor.copy(alpha = 0.25f)
                    )
                }
                is MdBlock.Table -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(codeBg, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        block.rows.forEachIndexed { ri, row ->
                            Text(
                                text = row.joinToString(" | "),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = if (ri == 0) FontWeight.Bold else FontWeight.Normal,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 18.sp
                                ),
                                color = bodyColor,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
                is MdBlock.Code -> {
                    val codeModifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(codeBg, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                    val codeBlock: @Composable () -> Unit = {
                        Text(
                            text = block.code,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 20.sp
                            ),
                            color = bodyColor,
                            modifier = codeModifier
                        )
                    }
                    if (allowSelection) {
                        SelectionContainer { codeBlock() }
                    } else {
                        codeBlock()
                    }
                }
                is MdBlock.Quote -> {
                    Text(
                        text = inlineMarkdown(block.text, bodyColor, onLinkClick),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = 22.sp,
                            fontStyle = FontStyle.Italic
                        ),
                        color = bodyColor.copy(alpha = 0.9f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .background(codeBg, RoundedCornerShape(4.dp))
                            .padding(start = 10.dp, top = 4.dp, end = 8.dp, bottom = 4.dp)
                    )
                }
                is MdBlock.Heading -> {
                    Text(
                        text = inlineMarkdown(block.text, bodyColor, onLinkClick),
                        style = when (block.level) {
                            1 -> MaterialTheme.typography.titleLarge
                            2 -> MaterialTheme.typography.titleMedium
                            else -> MaterialTheme.typography.titleSmall
                        }.copy(fontWeight = FontWeight.Bold, lineHeight = 24.sp),
                        color = bodyColor,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
                is MdBlock.ListItem -> {
                    Text(
                        text = inlineMarkdown("${block.bullet} ${block.text}", bodyColor, onLinkClick),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                        color = bodyColor,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
                is MdBlock.Paragraph -> {
                    Text(
                        text = inlineMarkdown(block.text, bodyColor, onLinkClick),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                        color = bodyColor
                    )
                }
            }
        }
    }
}

internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class ListItem(val bullet: String, val text: String) : MdBlock
    data class Code(val code: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
    data object Hr : MdBlock
}
