package com.maodouchat.server.repository


data class CreatedConversation(val id: String)

enum class ConversationCreationFailure {
    PARTICIPANTS_EMPTY,
    CREATOR_NOT_PARTICIPANT,
    PARTICIPANT_NOT_FOUND,
    PARTICIPANT_BLOCKED,
    DIRECT_SELF,
}

class ConversationCreationRejected(
    val failure: ConversationCreationFailure,
    val userId: String? = null,
) : IllegalArgumentException(failure.name.lowercase())

data class ConversationParticipantValidation(
    val failure: ConversationCreationFailure? = null,
    val missingUserId: String? = null,
) {
    val valid: Boolean get() = failure == null
}

/** Transactional write model for group, direct and secret conversation creation. */
class ConversationCreationRepository {
}
