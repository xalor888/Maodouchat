package com.maodouchat.domain.conversation


// 聊天内搜索模式种类；与同模块的 ChatSearchMode 1:1 映射。
enum class ChatSearchModeKind {
    KEYWORD,
    SEMANTIC,
}

fun chatSearchModeKind(mode: ChatSearchMode): ChatSearchModeKind =
    when (mode) {
        ChatSearchMode.KEYWORD -> ChatSearchModeKind.KEYWORD
        ChatSearchMode.SEMANTIC -> ChatSearchModeKind.SEMANTIC
    }
