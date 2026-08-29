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

/**
 * Single source of privacy restrictions for command entry points.
 *
 * Secret conversations permit foreground text, retries, and forwards, but reject background
 * staging paths that cannot expose the secret conversation safely. A locked conversation rejects
 * all durable commands until its caller obtains an unlocked context.
 */
class ConversationPrivacyPolicy {
    fun evaluate(
        capability: ConversationCapability,
        context: ConversationPrivacyContext,
    ): ConversationPrivacyDecision = when {
        context.isLocked -> ConversationPrivacyDecision.Rejected(
            ConversationPrivacyRejection.LOCKED_CONVERSATION,
        )
        context.isSecret && capability in BACKGROUND_CAPABILITIES -> ConversationPrivacyDecision.Rejected(
            ConversationPrivacyRejection.SECRET_CONVERSATION,
        )
        else -> ConversationPrivacyDecision.Allowed
    }

    private companion object {
        val BACKGROUND_CAPABILITIES = setOf(
            ConversationCapability.SCHEDULE,
            ConversationCapability.QUICK_REPLY,
        )
    }
}
