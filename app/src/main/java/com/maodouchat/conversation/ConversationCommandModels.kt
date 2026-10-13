package com.maodouchat.conversation

import com.maodouchat.data.model.Message

sealed interface ConversationCommandOutcome {
    data class Staged(val message: Message) : ConversationCommandOutcome
    data class Rejected(val reason: ConversationCommandRejection) : ConversationCommandOutcome
}

enum class ConversationCommandRejection {
    EMPTY_TEXT,
    CHAT_UNAVAILABLE,
    TERMINAL_MESSAGE,
    SECRET_CONVERSATION,
    LOCKED_CONVERSATION,
}
