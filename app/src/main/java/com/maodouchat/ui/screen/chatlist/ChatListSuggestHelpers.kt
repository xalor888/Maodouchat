package com.maodouchat.ui.screen.chatlist

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import com.maodouchat.ui.component.SearchHighlightAccent

// G156：原私有副本（18 行）收敛到 ui/component/SearchHighlightText.kt，此处仅剩薄包装。
@Composable
internal fun accentHighlightedText(text: String, query: String): AnnotatedString {
    val (c, bg) = SearchHighlightAccent
    return com.maodouchat.ui.component.highlightedText(text, query, c, bg)
}
