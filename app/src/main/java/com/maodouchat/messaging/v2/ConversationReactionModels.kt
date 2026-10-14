package com.maodouchat.messaging.v2

internal sealed interface ConversationReactionOutcome {
    data class Applied(
        val localProjectionError: Throwable? = null,
    ) : ConversationReactionOutcome

    data class Failed(
        val error: Throwable,
    ) : ConversationReactionOutcome

    data object Ignored : ConversationReactionOutcome
}
