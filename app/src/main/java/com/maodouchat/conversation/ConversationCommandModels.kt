package com.maodouchat.conversation

import com.maodouchat.core.model.ConversationCommandRejection
import com.maodouchat.core.model.Message

sealed interface ConversationCommandOutcome {
    data class Staged(val message: Message) : ConversationCommandOutcome
    data class Rejected(val reason: ConversationCommandRejection) : ConversationCommandOutcome
}

