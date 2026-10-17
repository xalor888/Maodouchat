package com.maodouchat.ui.screen.chatlist

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import com.maodouchat.data.model.Chat
import com.maodouchat.ui.component.SearchHighlightAccent

internal fun chatNameForSuggestion(chat: Chat?): String? {
    if (chat == null) return null
    return chat.groupName
        ?: chat.participants.firstOrNull()?.displayName
        ?: chat.participants.firstOrNull()?.name
}

// G156：原私有副本（18 行）收敛到 ui/component/SearchHighlightText.kt，此处仅剩薄包装。
@Composable
internal fun accentHighlightedText(text: String, query: String): AnnotatedString {
    val (c, bg) = SearchHighlightAccent
    return com.maodouchat.ui.component.highlightedText(text, query, c, bg)
}
