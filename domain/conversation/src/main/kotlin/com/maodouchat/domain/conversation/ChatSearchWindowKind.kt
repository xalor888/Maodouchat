package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatSearchWindow

// 聊天内搜索时间窗种类；时间窗枚举本身在 :core:model，domain 只做 1:1 映射。
enum class ChatSearchWindowKind {
    ALL,
    TODAY,
    SEVEN_DAYS,
    THIRTY_DAYS,
}

fun chatSearchWindowKind(window: ChatSearchWindow): ChatSearchWindowKind =
    when (window) {
        ChatSearchWindow.ALL -> ChatSearchWindowKind.ALL
        ChatSearchWindow.TODAY -> ChatSearchWindowKind.TODAY
        ChatSearchWindow.SEVEN_DAYS -> ChatSearchWindowKind.SEVEN_DAYS
        ChatSearchWindow.THIRTY_DAYS -> ChatSearchWindowKind.THIRTY_DAYS
    }
