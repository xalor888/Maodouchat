package com.maodouchat.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.maodouchat.ui.theme.LocalSentBubbleContent
import com.maodouchat.ui.theme.LocalSentBubbleContentSecondary
import com.maodouchat.ui.theme.OnSurface
import com.maodouchat.ui.theme.TextHint


// ─── CONTACT_CARD_USER_RE ───
internal val CONTACT_CARD_USER_RE = Regex("\\[contactUser:([^\\]]+)")

// ─── RichTextContent ───
@Composable
internal fun RichTextContent(
    text: String,
    mentionedUserIds: List<String>,
    isOwnMessage: Boolean,
    onContactCardClick: ((String) -> Unit)? = null,
    onLinkClick: (String) -> Unit = {},
    // 9.266：TG 式内嵌时间戳——非空时追加在正文最后一行行尾（小字号次色）
    inlineTimeSuffix: String? = null
) {
    // TG 式行尾时间戳 span：两空格间隔 + 11sp 次色，与正文同段落自然折行
    val inlineTimeColor = if (isOwnMessage) LocalSentBubbleContentSecondary.current else TextHint
    fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlineTime(time: String) {
        append("  ")
        withStyle(
            androidx.compose.ui.text.SpanStyle(
                fontSize = 11.sp,
                color = inlineTimeColor
            )
        ) {
            append(time)
        }
    }
    // 1.11：先剥离名片标记，接收端不会看到裸 [contactUser:...]（1.18 复用 ChatMarkdown 统一实现）
    val cleanText = com.maodouchat.messaging.ChatMarkdown.stripContactCardMarker(text)
    // 1.17：名片消息整体渲染为可点击链接（点击打开该用户资料）
    val cardUserId = remember(text) { CONTACT_CARD_USER_RE.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } }
    if (cardUserId != null) {
        val cardUrl = "contactcard://$cardUserId"
        val annotatedCard = androidx.compose.ui.text.buildAnnotatedString {
            withLink(
                androidx.compose.ui.text.LinkAnnotation.Clickable(
                    tag = cardUrl,
                    linkInteractionListener = androidx.compose.ui.text.LinkInteractionListener { onLinkClick(cardUrl) }
                )
            ) {
                withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = if (isOwnMessage) LocalSentBubbleContent.current else androidx.compose.ui.graphics.Color(0xFF4CAF50),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                ) {
                    append(cleanText.ifBlank { text })
                }
            }
        }
        Text(
            text = annotatedCard,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
            color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
        )
        return
    }
    val mentionColor = androidx.compose.ui.graphics.Color(0xFFFFC107)
    val hasAt = cleanText.contains('@')
    val urlRanges = remember(cleanText) { findUrlRanges(cleanText) }
    if (!hasAt && mentionedUserIds.isEmpty() && urlRanges.isEmpty()) {
        if (inlineTimeSuffix == null) {
            Text(
                text = cleanText,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
            )
        } else {
            Text(
                text = androidx.compose.ui.text.buildAnnotatedString {
                    append(cleanText)
                    appendInlineTime(inlineTimeSuffix)
                },
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
                color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
            )
        }
        return
    }
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        var i = 0
        while (i < cleanText.length) {
            // 优先匹配 URL（避免 @ 把 URL 内片段误判为 mention）
            val urlHit = urlRanges.firstOrNull { it.first == i }
            if (urlHit != null) {
                val (start, end) = urlHit
                val url = cleanText.substring(start, end)
                withLink(
                    androidx.compose.ui.text.LinkAnnotation.Clickable(
                        tag = url,
                        linkInteractionListener = androidx.compose.ui.text.LinkInteractionListener { onLinkClick(url) }
                    )
                ) {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = mentionColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) {
                        append(url)
                    }
                }
                i = end
                continue
            }
            if (cleanText[i] != '@' || (i > 0 && !cleanText[i - 1].isWhitespace())) {
                append(cleanText[i])
                i++
                continue
            }
            // 从 @ 扫到空白/标点
            var j = i + 1
            while (j < cleanText.length) {
                val ch = cleanText[j]
                if (ch.isWhitespace() || ch == ',' || ch == '.' || ch == '!' || ch == '?' ||
                    ch == '，' || ch == '。' || ch == '！' || ch == '？'
                ) break
                j++
            }
            if (j > i + 1) {
                withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = mentionColor,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                ) {
                    append(cleanText.substring(i, j))
                }
                i = j
            } else {
                append('@')
                i++
            }
        }
        // 9.266：mention/URL 混排分支同样追加行尾时间戳
        if (inlineTimeSuffix != null) appendInlineTime(inlineTimeSuffix)
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 22.sp),
        color = if (isOwnMessage) LocalSentBubbleContent.current else OnSurface
    )
}

/** 扫描文本中的 http/https URL 起止区间（左闭右开），供 [RichTextContent] 渲染可点击链接。 */

// ─── findUrlRanges ───
internal fun findUrlRanges(text: String): List<Pair<Int, Int>> {
    val ranges = mutableListOf<Pair<Int, Int>>()
    var i = 0
    while (i < text.length) {
        val start = if (text.startsWith("http://", i) || text.startsWith("https://", i)) i else -1
        if (start < 0) { i++; continue }
        var end = start
        while (end < text.length && !text[end].isWhitespace() && text[end] !in setOf('<', '>', '"', '\'')) {
            end++
        }
        while (end > start && text[end - 1] in setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')) {
            end--
        }
        if (end > start) ranges += start to end
        i = end.coerceAtLeast(start + 1)
    }
    return ranges
}
