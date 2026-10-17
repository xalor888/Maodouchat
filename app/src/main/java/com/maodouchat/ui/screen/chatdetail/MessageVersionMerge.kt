package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.crypto.DecryptPlaceholderPolicy
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.ChatListPreviewPolicy

/** Incoming versions are authoritative unless they would regress an edit or terminal revoke. */
internal fun mergeMessageVersions(existing: List<Message>, incoming: List<Message>): List<Message> {
    val byId = linkedMapOf<String, Message>()
    (existing + incoming).forEach { candidate ->
        val current = byId[candidate.id]
        byId[candidate.id] = if (current == null) candidate else chooseMessageVersion(current, candidate)
    }
    return byId.values.sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
}

internal fun chooseMessageVersion(current: Message, candidate: Message): Message {
    if (current.type == MessageType.REVOKED && candidate.type != MessageType.REVOKED) return current
    if (candidate.type == MessageType.REVOKED) return candidate

    val currentEdit = current.editedAt ?: Long.MIN_VALUE
    val candidateEdit = candidate.editedAt ?: Long.MIN_VALUE
    // 即使 candidate 的 editedAt 更新，也不得用密文/解密失败覆盖可读明文
    return when {
        candidateEdit > currentEdit -> {
            val preferred = preferReadableContent(current, candidate)
            preferred.copy(
                editedAt = candidate.editedAt,
                // Newer edit revision is authoritative for star/reactions (allows unstar / clear).
                starred = candidate.starred,
                reactions = candidate.reactions,
                status = higherDeliveryStatus(current.status, candidate.status)
            )
        }
        candidateEdit < currentEdit -> {
            val preferred = preferReadableContent(candidate, current)
            preferred.copy(
                editedAt = current.editedAt,
                // Keep the newer-revision metadata already on current.
                starred = current.starred,
                reactions = current.reactions,
                status = higherDeliveryStatus(current.status, candidate.status)
            )
        }
        // Equal/missing editedAt: accept server snapshot for content/star/reactions,
        // but never regress a higher local delivery status (READ/DELIVERED).
        else -> mergeEqualRevision(current, candidate)
    }
}

internal fun mergeEqualRevision(current: Message, candidate: Message): Message {
    val preferredStatus = higherDeliveryStatus(current.status, candidate.status)
    // Prefer non-encrypted successful plaintext over re-decrypt failure placeholders /
    // wire ciphertext. Prevents open→reopen from wiping already-readable messages.
    // Equal revision: candidate is authoritative for star/reactions (allows clear).
    val preferredContent = preferReadableContent(current, candidate)
    return preferredContent.copy(
        status = preferredStatus,
        starred = candidate.starred,
        reactions = candidate.reactions,
        editedAt = candidate.editedAt ?: current.editedAt
    )
}

/**
 * When both versions share the same edit revision, keep readable local plaintext if
 * the other side looks like ciphertext or a decrypt-failure placeholder.
 */
internal fun preferReadableContent(current: Message, candidate: Message): Message {
    val currentLooksEncrypted = looksLikeEncryptedEnvelope(current.content)
    val candidateLooksEncrypted = looksLikeEncryptedEnvelope(candidate.content)
    val currentLooksFailure = looksLikeDecryptFailurePlaceholder(current.content)
    val candidateLooksFailure = looksLikeDecryptFailurePlaceholder(candidate.content)

    return when {
        // Good plaintext vs ciphertext/failure → keep plaintext
        !currentLooksEncrypted && !currentLooksFailure && (candidateLooksEncrypted || candidateLooksFailure) -> current
        !candidateLooksEncrypted && !candidateLooksFailure && (currentLooksEncrypted || currentLooksFailure) -> candidate
        // Both failure placeholders → keep current (stable UI)
        currentLooksFailure && candidateLooksFailure -> current
        else -> candidate
    }
}

internal fun looksLikeEncryptedEnvelope(content: String): Boolean {
    if (content.isBlank() || content.length < 16) return false
    if (ChatListPreviewPolicy.isSignalWireEnvelope(content)) return true
    // Server/client envelopes are base64-ish JSON or multi-device markers; cheap heuristic
    return content.startsWith("eyJ") || // base64 "{"
        content.startsWith("{") && (content.contains("\"devices\"") || content.contains("\"ciphertext\"") || content.contains("\"type\"")) ||
        content.startsWith("MD:") || content.startsWith("SK:")
}

internal fun looksLikeDecryptFailurePlaceholder(content: String): Boolean {
    return DecryptPlaceholderPolicy.isPlaceholder(content)
}

internal fun higherDeliveryStatus(a: MessageStatus, b: MessageStatus): MessageStatus {
    if (a == MessageStatus.FAILED) return b
    if (b == MessageStatus.FAILED) return a
    return if (a.deliveryRank() >= b.deliveryRank()) a else b
}
