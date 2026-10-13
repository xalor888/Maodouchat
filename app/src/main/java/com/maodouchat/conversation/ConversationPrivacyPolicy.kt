package com.maodouchat.conversation

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
