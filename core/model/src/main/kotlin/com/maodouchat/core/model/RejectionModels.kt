package com.maodouchat.core.model

enum class ConversationCommandRejection {
    EMPTY_TEXT,
    CHAT_UNAVAILABLE,
    TERMINAL_MESSAGE,
    SECRET_CONVERSATION,
    LOCKED_CONVERSATION,
}

enum class ChatScheduleRejection {
    DISABLED,
    INVALID_REQUEST,
    SESSION_MISSING,
    BLOCKED,
    LIMIT_REACHED,
    NOT_FOUND,
    STORAGE,
}

enum class ConversationScheduleFailure {
    SESSION_MISSING,
    INVALID_REQUEST,
    LIMIT_REACHED,
    NOT_FOUND,
    STORAGE,
}
