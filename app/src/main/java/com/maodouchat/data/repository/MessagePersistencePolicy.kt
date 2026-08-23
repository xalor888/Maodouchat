package com.maodouchat.data.repository

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType

/**
 * Room write-path merge for message rows.
 *
 * Delivery status stays monotonic. Content prefers readable plaintext over wire
 * ciphertext / decrypt placeholders. Star and reactions treat the newer (or equal)
 * revision writer snapshot as authoritative so unstar and empty reaction lists stick.
 */
internal fun mergeMessageForPersistence(existing: Message, incoming: Message): Message {
    if (existing.type == MessageType.REVOKED && incoming.type != MessageType.REVOKED) return existing
    if (incoming.type == MessageType.REVOKED) return incoming
    val existingEdit = existing.editedAt ?: Long.MIN_VALUE
    val incomingEdit = incoming.editedAt ?: Long.MIN_VALUE
    val preferred = when {
        incomingEdit > existingEdit -> preferReadablePersistence(existing, incoming)
        incomingEdit < existingEdit -> preferReadablePersistence(incoming, existing)
        else -> preferReadablePersistence(existing, incoming)
    }
    // Star/reactions: newer or equal revision uses the incoming writer snapshot.
    // Older incoming must not clobber metadata already on the preferred newer row.
    val base = if (incomingEdit >= existingEdit) {
        preferred.copy(
            editedAt = if (incomingEdit > existingEdit) incoming.editedAt else preferred.editedAt,
            starred = incoming.starred,
            reactions = incoming.reactions
        )
    } else {
        preferred.copy(
            starred = preferred.starred,
            reactions = preferred.reactions
        )
    }
    val withStatus = base.copy(status = mergeDeliveryStatusForPersistence(existing.status, incoming.status))
    return preserveLocalMediaFlags(existing, incoming, withStatus)
}

private fun preserveLocalMediaFlags(existing: Message, incoming: Message, merged: Message): Message {
    val existingMeta = existing.parsedMeta()
    val incomingMeta = incoming.parsedMeta()
    val mergedMeta = merged.parsedMeta()
    val protectedMeta = mergedMeta.copy(
        viewOnce = mergedMeta.viewOnce || existingMeta.viewOnce || incomingMeta.viewOnce,
        viewOnceOpened = mergedMeta.viewOnceOpened || existingMeta.viewOnceOpened || incomingMeta.viewOnceOpened,
        spoilerMedia = mergedMeta.spoilerMedia || existingMeta.spoilerMedia || incomingMeta.spoilerMedia,
        spoilerRevealed = mergedMeta.spoilerRevealed || existingMeta.spoilerRevealed || incomingMeta.spoilerRevealed
    )
    return if (protectedMeta == mergedMeta) merged else merged.withEncodedMeta(protectedMeta)
}

internal fun mergeLocalMediaMetaForPersistence(current: Message, localSnapshot: Message): Message {
    val currentMeta = current.parsedMeta()
    val localMeta = localSnapshot.parsedMeta()
    val mergedMeta = currentMeta.copy(
        viewOnce = currentMeta.viewOnce || localMeta.viewOnce,
        viewOnceOpened = currentMeta.viewOnceOpened || localMeta.viewOnceOpened,
        spoilerMedia = currentMeta.spoilerMedia || localMeta.spoilerMedia,
        spoilerRevealed = currentMeta.spoilerRevealed || localMeta.spoilerRevealed
    )
    return if (mergedMeta == currentMeta) current else current.withEncodedMeta(mergedMeta)
}

private fun preferReadablePersistence(existing: Message, incoming: Message): Message {
    val existingGood = isReadablePlaintextForPersistence(existing.content)
    val incomingGood = isReadablePlaintextForPersistence(incoming.content)
    return when {
        existingGood && !incomingGood -> existing
        !existingGood && incomingGood -> incoming
        existingGood && incomingGood -> incoming
        else -> preferWireOverPlaceholder(existing, incoming)
    }
}

/** Ciphertext can be retried after SK/session repair; UI placeholders cannot. */
private fun preferWireOverPlaceholder(existing: Message, incoming: Message): Message {
    val existingWire = looksLikePersistedWire(existing.content)
    val incomingWire = looksLikePersistedWire(incoming.content)
    return when {
        existingWire && !incomingWire -> existing
        incomingWire && !existingWire -> incoming
        else -> incoming
    }
}

private fun looksLikePersistedWire(content: String): Boolean {
    if (content.isBlank()) return false
    if (content.startsWith("eyJ")) return true
    if (content.startsWith("MD:") || content.startsWith("SK:")) return true
    return ChatListPreviewPolicy.looksLikeWireEnvelope(content) ||
        ChatListPreviewPolicy.isSignalWireEnvelope(content)
}

private fun isReadablePlaintextForPersistence(content: String): Boolean {
    if (content.isBlank()) return false
    if (content.startsWith("eyJ")) return false
    if (content.startsWith("MD:") || content.startsWith("SK:")) return false
    if (content.startsWith("{") &&
        (content.contains("\"ciphertext\"") || content.contains("\"devices\""))
    ) {
        return false
    }
    val lower = content.lowercase()
    if (lower.contains("解密") || lower.contains("decrypt") ||
        (lower.contains("密钥") && (lower.contains("缺失") || lower.contains("失败")))
    ) {
        return false
    }
    return true
}

/**
 * Prefer higher receipt ladder (SENT→DELIVERED→READ).
 * FAILED may replace SENDING (local send error) but must not clobber SENT+.
 * Remote snapshots without FAILED should not erase a local FAILED still awaiting retry.
 */
internal fun mergeDeliveryStatusForPersistence(
    existing: MessageStatus,
    incoming: MessageStatus
): MessageStatus {
    if (existing == incoming) return existing
    if (incoming == MessageStatus.FAILED) {
        return if (existing == MessageStatus.SENDING || existing == MessageStatus.FAILED) {
            MessageStatus.FAILED
        } else {
            existing
        }
    }
    if (existing == MessageStatus.FAILED) {
        // Allow retry paths that move FAILED → SENDING/SENT
        return if (incoming == MessageStatus.SENDING || incoming == MessageStatus.SENT) {
            incoming
        } else {
            existing
        }
    }
    return if (existing.deliveryRank() >= incoming.deliveryRank()) existing else incoming
}
