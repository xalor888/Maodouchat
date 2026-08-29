package com.maodouchat.data.repository

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType

/** Deterministic local conflict rules shared by own-device and linked-device projections. */
internal fun applyEditedMessageVersion(existing: Message, candidate: Message): Message? {
    if (existing.id != candidate.id || existing.type == MessageType.REVOKED) return null
    val candidateRevision = candidate.editedAt ?: return null
    val existingRevision = existing.editedAt ?: Long.MIN_VALUE
    if (candidateRevision < existingRevision) return null
    if (candidateRevision == existingRevision && candidate.content < existing.content) return null
    if (candidateRevision == existingRevision && candidate.content == existing.content) return existing
    return existing.copy(
        content = candidate.content,
        editedAt = candidateRevision,
        meta = candidate.meta,
    )
}

internal fun applyRevokedMessageVersion(existing: Message, candidate: Message): Message? {
    if (existing.id != candidate.id) return null
    val candidateRevision = candidate.editedAt ?: Long.MIN_VALUE
    val existingRevision = existing.editedAt ?: Long.MIN_VALUE
    if (
        existing.type == MessageType.REVOKED &&
        (candidateRevision < existingRevision ||
            (candidateRevision == existingRevision && candidate.content < existing.content))
    ) {
        return existing
    }
    return existing.copy(
        type = MessageType.REVOKED,
        content = candidate.content,
        editedAt = maxOf(existingRevision, candidateRevision).takeUnless { it == Long.MIN_VALUE },
        reactions = emptyList(),
    )
}
