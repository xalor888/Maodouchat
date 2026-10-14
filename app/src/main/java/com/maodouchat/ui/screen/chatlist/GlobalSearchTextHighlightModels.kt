package com.maodouchat.ui.screen.chatlist

data class HighlightSpan(val start: Int, val end: Int) {
    init {
        require(start >= 0 && end >= start)
    }
}

data class SearchSnippet(
    val text: String,
    /** Spans relative to [text], not the original full body. */
    val highlights: List<HighlightSpan>
)
