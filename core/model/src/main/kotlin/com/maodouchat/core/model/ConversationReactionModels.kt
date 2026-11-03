package com.maodouchat.core.model

sealed interface ConversationReactionOutcome {
    data class Applied(
        val localProjectionError: Throwable? = null,
    ) : ConversationReactionOutcome

    data class Failed(
        val error: Throwable,
    ) : ConversationReactionOutcome

    data object Ignored : ConversationReactionOutcome
}
