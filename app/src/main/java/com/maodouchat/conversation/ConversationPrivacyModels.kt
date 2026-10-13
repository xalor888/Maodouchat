package com.maodouchat.conversation

/** Conversation capabilities used by every domain entry point before a durable command is staged. */
enum class ConversationCapability {
    SEND_TEXT,
    RETRY_MESSAGE,
    FORWARD,
    SCHEDULE,
    QUICK_REPLY,
}

data class ConversationPrivacyContext(
    val isSecret: Boolean = false,
    val isLocked: Boolean = false,
)

sealed interface ConversationPrivacyDecision {
    data object Allowed : ConversationPrivacyDecision
    data class Rejected(val reason: ConversationPrivacyRejection) : ConversationPrivacyDecision
}

enum class ConversationPrivacyRejection {
    SECRET_CONVERSATION,
    LOCKED_CONVERSATION,
}
