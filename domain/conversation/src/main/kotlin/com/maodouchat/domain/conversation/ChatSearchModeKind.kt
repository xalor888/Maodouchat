package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatSearchMode

// 聊天内搜索模式种类；模式枚举本身在 :core:model，domain 只做 1:1 映射。
enum class ChatSearchModeKind {
    KEYWORD,
    SEMANTIC,
}

fun chatSearchModeKind(mode: ChatSearchMode): ChatSearchModeKind =
    when (mode) {
        ChatSearchMode.KEYWORD -> ChatSearchModeKind.KEYWORD
        ChatSearchMode.SEMANTIC -> ChatSearchModeKind.SEMANTIC
    }
