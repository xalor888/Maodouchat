package com.maodouchat.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.maodouchat.ui.screen.chatlist.GlobalSearchTextHighlight

/**
 * 搜索结果高亮（G156：从全 app **12 处**重复的私有 `highlightedText` 收敛而来）。
 *
 * 原先每个屏幕都有一份 18 行的私有副本，只差高亮配色，共约 220 行重复。
 * 两种既有配色保留为命名常量，调用方按原样选一个，**不改变任何一处的视觉**：
 * - [SearchHighlightAccent] —— `primary` 文字 + `primary α=0.12` 背景（9 处）
 * - [SearchHighlightSurface] —— `onSurface` 文字 + `primaryContainer` 背景（3 处）
 *
 *逻辑与原先完全一致：`remember(text, query)` 缓存 snippet，
 * 无高亮直接返回原文，有高亮则按 span 切片并 push/pop 样式。
 */
@Composable
internal fun highlightedText(
    text: String,
    query: String,
    highlightColor: Color,
    highlightBackground: Color,
): AnnotatedString {
    val snippet = remember(text, query) {
        GlobalSearchTextHighlight.buildSnippet(text, query)
    }
    return buildAnnotatedString {
        if (snippet.highlights.isEmpty()) {
            append(snippet.text)
            return@buildAnnotatedString
        }
        var cursor = 0
        snippet.highlights.forEach { span ->
            if (span.start > cursor) append(snippet.text.substring(cursor, span.start))
            pushStyle(
                SpanStyle(
                    color = highlightColor,
                    fontWeight = FontWeight.SemiBold,
                    background = highlightBackground,
                )
            )
            append(snippet.text.substring(span.start, span.end))
            pop()
            cursor = span.end
        }
        if (cursor < snippet.text.length) append(snippet.text.substring(cursor))
    }
}

/** `primary` 文字 + `primary α=0.12` 背景（原 9 处私有副本的配色）。 */
val SearchHighlightAccent: Pair<Color, Color>
    @Composable get() = MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)

/** `onSurface` 文字 + `primaryContainer` 背景（原 3 处私有副本的配色）。 */
val SearchHighlightSurface: Pair<Color, Color>
    @Composable get() = MaterialTheme.colorScheme.onSurface to MaterialTheme.colorScheme.primaryContainer
