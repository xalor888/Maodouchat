package com.maodouchat.core.model


sealed interface ConversationCommandOutcome {
    data class Staged(val message: Message) : ConversationCommandOutcome
    data class Rejected(val reason: ConversationCommandRejection) : ConversationCommandOutcome
}

