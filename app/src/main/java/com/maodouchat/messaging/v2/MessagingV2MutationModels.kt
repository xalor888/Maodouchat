package com.maodouchat.messaging.v2

internal enum class MessageMutationKind { DELETE, REVOKE, EDIT }

internal data class MessageMutationTicket(
    val messageId: String,
    val kind: MessageMutationKind,
    val generation: Long,
)
