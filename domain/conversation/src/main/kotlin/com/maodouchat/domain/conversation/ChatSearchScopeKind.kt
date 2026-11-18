package com.maodouchat.domain.conversation


// 聊天内搜索范围种类；与同模块的 ChatSearchScope 1:1 映射。
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
