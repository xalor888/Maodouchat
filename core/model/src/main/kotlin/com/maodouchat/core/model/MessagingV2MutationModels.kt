package com.maodouchat.core.model

enum class MessageMutationKind { DELETE, REVOKE, EDIT }

data class MessageMutationTicket(
    val messageId: String,
    val kind: MessageMutationKind,
    val generation: Long,
)
