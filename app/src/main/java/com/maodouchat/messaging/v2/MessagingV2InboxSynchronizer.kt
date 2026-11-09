package com.maodouchat.messaging.v2

import com.maodouchat.data.local.dao.MessagingV2Dao
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import com.maodouchat.domain.messaging.InboxPullContinuation
import com.maodouchat.domain.messaging.InboxRetryPolicy
import com.maodouchat.domain.messaging.InboxSyncLimits
import com.maodouchat.domain.messaging.decideInboxPullContinuation
import com.maodouchat.domain.messaging.shouldFetchMoreAcknowledgements
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
                staleBefore = now - InboxSyncLimits.STALE_PROCESSING_MS,
                now = now,
            )
            flushAcknowledgements(token, ownerUserId, deviceId)
            repeat(InboxSyncLimits.MAX_PULL_PAGES) {
                val page = ApiService.getPendingInboxV2(token, InboxSyncLimits.PULL_LIMIT).getOrThrow()
                val pulledAt = clock()
                dao.insertInbox(page.envelopes.map { it.toEntity(ownerUserId, deviceId, pulledAt) })
                val batchCompleted = processAvailable(ownerUserId, deviceId)
                flushAcknowledgements(token, ownerUserId, deviceId)
                if (decideInboxPullContinuation(batchCompleted, page.hasMore) == InboxPullContinuation.STOP) return
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
                when (val decision = InboxRetryPolicy.decide(error, attempts, clock())) {
                    is InboxRetryPolicy.Decision.DeadLetter -> {
                        check(
                            dao.markInboxDeadLetterAckPending(
                                envelopeId = envelope.envelopeId,
                                errorCode = decision.errorCode,
                                now = clock(),
                            ) == 1,
                        ) { "messaging_v2_inbox_dead_letter_state_lost" }
                        continue
                    }
                    is InboxRetryPolicy.Decision.Retry -> {
                        dao.markInboxFailed(
                            envelopeId = envelope.envelopeId,
                            errorCode = decision.errorCode,
                            nextAttemptAt = decision.nextAttemptAt,
                            now = clock(),
                        )
                        // Preserve delivery order. A later envelope may share the failed ratchet.
                        return false
                    }
                }
            }
        }
    }

    private suspend fun flushAcknowledgements(token: String, ownerUserId: String, deviceId: Int) {
        while (true) {
            val ids = dao.ackPendingIds(ownerUserId, deviceId, InboxSyncLimits.ACK_LIMIT)
            if (ids.isEmpty()) return
            val response = ApiService.acknowledgeInboxV2(token, ids).getOrThrow()
            check(response.acknowledged == ids.size) { "messaging_v2_ack_incomplete" }
            dao.deleteAcknowledgedInbox(ownerUserId, deviceId, ids)
            dao.markDeadLettersAcknowledged(ownerUserId, deviceId, ids, clock())
            if (!shouldFetchMoreAcknowledgements(ids.size, InboxSyncLimits.ACK_LIMIT)) return
        }
    }

    // 分页与兜底常量见 :domain:messaging 的 InboxSyncLimits；测试直接引用 domain 常量，值由其测试钉住。
}
