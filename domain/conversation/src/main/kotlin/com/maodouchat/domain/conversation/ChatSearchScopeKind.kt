package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatSearchScope

// 聊天内搜索范围种类；范围枚举本身在 :core:model，domain 只做 1:1 映射。
enum class ChatSearchScopeKind {
    ALL,
    TEXT,
    VOICE,
    TRANSLATION,
    STARRED,
    MEDIA,
    MENTIONS,
}

fun chatSearchScopeKind(scope: ChatSearchScope): ChatSearchScopeKind =
    when (scope) {
        ChatSearchScope.ALL -> ChatSearchScopeKind.ALL
        ChatSearchScope.TEXT -> ChatSearchScopeKind.TEXT
        ChatSearchScope.VOICE -> ChatSearchScopeKind.VOICE
        ChatSearchScope.TRANSLATION -> ChatSearchScopeKind.TRANSLATION
        ChatSearchScope.STARRED -> ChatSearchScopeKind.STARRED
        ChatSearchScope.MEDIA -> ChatSearchScopeKind.MEDIA
        ChatSearchScope.MENTIONS -> ChatSearchScopeKind.MENTIONS
    }
