package com.maodouchat.core.model


sealed interface MessageMutationProjection {
    data class Remove(val messageId: String) : MessageMutationProjection
    data class Set(val message: Message) : MessageMutationProjection
}

sealed interface ConversationMessageMutationOutcome {
    data class Applied(
        val removePinnedReference: Boolean,
        val localProjectionError: Throwable? = null,
        val authoritativeElsewhere: Boolean = false,
    ) : ConversationMessageMutationOutcome

    data class Failed(
        val error: Throwable,
        val rolledBack: Boolean,
    ) : ConversationMessageMutationOutcome

    data object Ignored : ConversationMessageMutationOutcome
}
