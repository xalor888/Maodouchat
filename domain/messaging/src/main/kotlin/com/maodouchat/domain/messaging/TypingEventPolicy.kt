package com.maodouchat.domain.messaging

fun shouldApplyTypingEvent(activeChatId: String, eventChatId: String): Boolean =
    activeChatId.isNotBlank() && activeChatId == eventChatId

const val REMOTE_TYPING_TIMEOUT_MS = 3_000L
