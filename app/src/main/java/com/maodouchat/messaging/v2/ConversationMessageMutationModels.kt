package com.maodouchat.messaging.v2

import com.maodouchat.data.model.Message

internal sealed interface MessageMutationProjection {
    data class Remove(val messageId: String) : MessageMutationProjection
    data class Set(val message: Message) : MessageMutationProjection
}

internal sealed interface ConversationMessageMutationOutcome {
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
