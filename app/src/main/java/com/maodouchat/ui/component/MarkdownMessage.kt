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
import com.maodouchat.messaging.ChatMarkdown


/**
 * Lightweight chat Markdown subset:
 * headings, bold, italic, strike, inline code, fenced code, links, lists, quotes.
 * No HTML. Safe for E2EE plaintext after local decrypt.
 */

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
