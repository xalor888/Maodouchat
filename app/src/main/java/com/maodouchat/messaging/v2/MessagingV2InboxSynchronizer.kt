package com.maodouchat.messaging.v2

import com.maodouchat.data.local.dao.MessagingV2Dao
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import com.maodouchat.network.ApiService
import com.maodouchat.network.toEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface MessagingV2EnvelopeProcessor {
    /** Must commit decrypted domain data idempotently before returning. */
    suspend fun process(envelope: MessagingV2InboxEntity)
}

/**
 * The sole owner of v2 receive ordering. UI screens and WebSocket collectors may only trigger it;
 * they never decrypt or persist an envelope themselves.
 */
class MessagingV2InboxSynchronizer(
    private val dao: MessagingV2Dao,
    private val processor: MessagingV2EnvelopeProcessor,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun sync(token: String, ownerUserId: String, deviceId: Int) {
        mutex.withLock {
            require(token.isNotBlank() && ownerUserId.isNotBlank() && deviceId > 0)
            val now = clock()
            dao.recoverStaleInboxClaims(
                ownerUserId = ownerUserId,
                deviceId = deviceId,
                staleBefore = now - STALE_PROCESSING_MS,
                now = now,
            )
            flushAcknowledgements(token, ownerUserId, deviceId)
            repeat(MAX_PULL_PAGES) {
                val page = ApiService.getPendingInboxV2(token, PULL_LIMIT).getOrThrow()
                val pulledAt = clock()
                dao.insertInbox(page.envelopes.map { it.toEntity(ownerUserId, deviceId, pulledAt) })
                val batchCompleted = processAvailable(ownerUserId, deviceId)
                flushAcknowledgements(token, ownerUserId, deviceId)
                if (!batchCompleted) return
                if (!page.hasMore) return
            }
        }
    }

    private suspend fun processAvailable(ownerUserId: String, deviceId: Int): Boolean {
        while (true) {
            val now = clock()
            val envelope = dao.claimNextInbox(ownerUserId, deviceId, now) ?: return true
            try {
                processor.process(envelope)
                check(dao.markInboxAckPending(envelope.envelopeId, clock()) == 1) {
                    "messaging_v2_inbox_state_lost"
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val attempts = envelope.attempts + 1
                val errorCode = error.message?.take(120)?.ifBlank { null }
                    ?: error::class.java.simpleName
                if (MessagingV2InboxFailurePolicy.shouldDeadLetter(errorCode, attempts)) {
                    check(
                        dao.markInboxDeadLetterAckPending(
                            envelopeId = envelope.envelopeId,
                            errorCode = errorCode,
                            now = clock(),
                        ) == 1,
                    ) { "messaging_v2_inbox_dead_letter_state_lost" }
                    continue
                }
                dao.markInboxFailed(
                    envelopeId = envelope.envelopeId,
                    errorCode = errorCode,
                    nextAttemptAt = MessagingV2RetryPolicy.nextAttemptAt(clock(), attempts),
                    now = clock(),
                )
                // Preserve delivery order. A later envelope may share the failed ratchet.
                return false
            }
        }
    }

    private suspend fun flushAcknowledgements(token: String, ownerUserId: String, deviceId: Int) {
        while (true) {
            val ids = dao.ackPendingIds(ownerUserId, deviceId, ACK_LIMIT)
            if (ids.isEmpty()) return
            val response = ApiService.acknowledgeInboxV2(token, ids).getOrThrow()
            check(response.acknowledged == ids.size) { "messaging_v2_ack_incomplete" }
            dao.deleteAcknowledgedInbox(ownerUserId, deviceId, ids)
            dao.markDeadLettersAcknowledged(ownerUserId, deviceId, ids, clock())
            if (ids.size < ACK_LIMIT) return
        }
    }

    private companion object {
        const val PULL_LIMIT = 200
        const val ACK_LIMIT = 200
        const val MAX_PULL_PAGES = 20
        const val STALE_PROCESSING_MS = 2L * 60L * 1_000L
    }
}
