package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatSearchMode
import com.maodouchat.core.model.ChatSearchScope

// 聊天内搜索框占位符种类；语义模式优先于星标范围，与原 when 分支顺序一致。
enum class ChatSearchPlaceholderKind {
    SEMANTIC,
    STARRED,
    CONTENT,
}

fun chatSearchPlaceholderKind(mode: ChatSearchMode, scope: ChatSearchScope): ChatSearchPlaceholderKind =
    when {
        mode == ChatSearchMode.SEMANTIC -> ChatSearchPlaceholderKind.SEMANTIC
        scope == ChatSearchScope.STARRED -> ChatSearchPlaceholderKind.STARRED
        else -> ChatSearchPlaceholderKind.CONTENT
    }
