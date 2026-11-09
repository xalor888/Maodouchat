package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessagingV2Content
import com.maodouchat.core.model.MessagingV2ContentPolicy
import kotlinx.serialization.json.Json

private val duplicateEnvelopeJson = Json { ignoreUnknownKeys = false }

/** Outcome of recovering a duplicate (replayed) envelope from the plaintext journal. */
sealed interface DuplicateEnvelopeRecovery {
    /** Nothing left to do: the message already reached the timeline. */
    data object Acknowledge : DuplicateEnvelopeRecovery

    /** No journal and no projection: the body is genuinely lost, fail loudly. */
    data object Uncommitted : DuplicateEnvelopeRecovery

    /** The journal holds committable content: commit it, then clear the journal. */
    data class Recover(val content: MessagingV2Content) : DuplicateEnvelopeRecovery

    /** The journal holds nothing committable: clear it so it cannot replay forever. */
    data object DiscardJournal : DuplicateEnvelopeRecovery
}

/**
 * Decides how a duplicate envelope recovers. A blank journal counts as no
 * journal. The projection check runs only when there is no usable journal,
 * so callers can pass a lazy lookup. The caller owns the side effects: commit
 * for [DuplicateEnvelopeRecovery.Recover], journal clearing for
 * [DuplicateEnvelopeRecovery.Recover] and [DuplicateEnvelopeRecovery.DiscardJournal],
 * and failing for [DuplicateEnvelopeRecovery.Uncommitted].
 */
suspend fun decideDuplicateRecovery(
    kind: String,
    journaledPlaintext: String?,
    isMessageProjected: suspend () -> Boolean,
): DuplicateEnvelopeRecovery {
    val journaled = journaledPlaintext?.takeIf(String::isNotBlank)
        ?: return if (isMessageProjected()) {
            DuplicateEnvelopeRecovery.Acknowledge
        } else {
            DuplicateEnvelopeRecovery.Uncommitted
        }
    val content = runCatching {
        duplicateEnvelopeJson.decodeFromString<MessagingV2Content>(journaled)
    }.getOrNull()
    return if (content != null && MessagingV2ContentPolicy.accepts(kind, content)) {
        DuplicateEnvelopeRecovery.Recover(content)
    } else {
        DuplicateEnvelopeRecovery.DiscardJournal
    }
}
