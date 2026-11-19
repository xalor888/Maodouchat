package com.maodouchat.domain.messaging

enum class ConversationCommandRejection {
    EMPTY_TEXT,
    CHAT_UNAVAILABLE,
    TERMINAL_MESSAGE,
    SECRET_CONVERSATION,
    LOCKED_CONVERSATION,
}
