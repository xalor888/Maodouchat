package com.maodouchat.core.model

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
