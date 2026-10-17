package com.maodouchat.ui.screen.chatlist

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import com.maodouchat.ui.component.SearchHighlightSurface

@Composable
internal fun highlightedText(text: String, query: String): AnnotatedString {
    val (c, bg) = SearchHighlightSurface
    return com.maodouchat.ui.component.highlightedText(text, query, c, bg)
}
