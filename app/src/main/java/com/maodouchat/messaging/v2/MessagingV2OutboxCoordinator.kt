package com.maodouchat.messaging.v2

import com.maodouchat.core.model.EncryptedDeviceEnvelopeRequestV2
import com.maodouchat.core.model.MessagingV2StaleGroupControlException
import com.maodouchat.core.model.SendMessageRequestV2
import com.maodouchat.data.local.dao.MessagingV2Dao
import com.maodouchat.data.local.entity.MessagingV2OutboxEntity
import com.maodouchat.data.local.entity.MessagingV2OutboxState
import com.maodouchat.domain.messaging.OutboxClaimAction
import com.maodouchat.domain.messaging.OutboxFailurePolicy
import com.maodouchat.domain.messaging.OutboxFlushLimits
import com.maodouchat.domain.messaging.OutboxRetryPolicy
import com.maodouchat.domain.messaging.decideOutboxClaimAction
import com.maodouchat.domain.messaging.outboxAttachmentIds
import com.maodouchat.network.ApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Persistent send state machine. Screens enqueue local commands and never perform transport. */
class MessagingV2OutboxCoordinator(
    private val dao: MessagingV2Dao,
    private val preparer: MessagingV2EnvelopePreparer,
    private val onCompleted: suspend (MessagingV2OutboxEntity) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    suspend fun flush(token: String, ownerUserId: String) {
        mutex.withLock {
            require(token.isNotBlank() && ownerUserId.isNotBlank())
            val now = clock()
            dao.recoverStaleOutboxClaims(
                ownerUserId = ownerUserId,
                staleBefore = now - OutboxFlushLimits.STALE_CLAIM_MS,
                now = now,
            )
            repeat(OutboxFlushLimits.MAX_MESSAGES_PER_RUN) {
                val claimed = dao.claimNextOutbox(ownerUserId, clock()) ?: return
                when (val action = decideOutboxClaimAction(claimed.state)) {
                    OutboxClaimAction.Prepare -> prepare(token, claimed)
                    OutboxClaimAction.Send -> send(token, claimed)
                    is OutboxClaimAction.Invalid ->
                        error("messaging_v2_invalid_claim_state:${action.state}")
                }
            }
        }
    }

    private suspend fun prepare(token: String, message: MessagingV2OutboxEntity) {
        try {
            val prepared = preparer.prepare(token, message)
            val encoded = json.encodeToString(
                ListSerializer(EncryptedDeviceEnvelopeRequestV2.serializer()),
                prepared.envelopes,
            )
            check(
                dao.storePreparedOutbox(
                    messageId = message.messageId,
                    ownerUserId = message.ownerUserId,
                    envelopesJson = encoded,
                    groupRevision = prepared.groupRevision,
                    now = clock(),
                ) == 1,
            ) { "messaging_v2_outbox_prepare_state_lost" }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (error is MessagingV2StaleGroupControlException) {
                check(
                    dao.discardOutbox(
                        message.messageId,
                        message.ownerUserId,
                        MessagingV2OutboxState.PREPARING,
                    ) == 1,
                ) { "messaging_v2_stale_control_discard_lost" }
                return
            }
            markFailure(
                message = message,
                expectedState = MessagingV2OutboxState.PREPARING,
                failedWhilePreparing = true,
                error = error,
            )
        }
    }

    private suspend fun send(token: String, message: MessagingV2OutboxEntity) {
        try {
            val encoded = requireNotNull(message.preparedEnvelopesJson) {
                "messaging_v2_prepared_envelopes_missing"
            }
            val envelopes = json.decodeFromString(
                ListSerializer(EncryptedDeviceEnvelopeRequestV2.serializer()),
                encoded,
            )
            ApiService.sendMessageV2(
                token,
                SendMessageRequestV2(
                    id = message.messageId,
                    conversationId = message.conversationId,
                    kind = message.kind,
                    clientTimestamp = message.clientTimestamp,
                    groupRevision = message.groupRevision,
                    attachmentIds = outboxAttachmentIds(message.localPayload),
                    envelopes = envelopes,
                ),
            ).getOrThrow()
            onCompleted(message)
            check(dao.completeOutbox(message.messageId, message.ownerUserId) == 1) {
                "messaging_v2_outbox_send_state_lost"
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (OutboxFailurePolicy.shouldDiscardStaleGroupControl(error, message.kind)) {
                check(
                    dao.discardOutbox(
                        message.messageId,
                        message.ownerUserId,
                        MessagingV2OutboxState.SENDING,
                    ) == 1,
                ) { "messaging_v2_stale_control_send_discard_lost" }
                return
            }
            markFailure(
                message = message,
                expectedState = MessagingV2OutboxState.SENDING,
                failedWhilePreparing = false,
                error = error,
            )
        }
    }

    private suspend fun markFailure(
        message: MessagingV2OutboxEntity,
        expectedState: String,
        failedWhilePreparing: Boolean,
        error: Exception,
    ) {
        val decision = OutboxRetryPolicy.decide(
            error = error,
            failedWhilePreparing = failedWhilePreparing,
            attempts = message.attempts + 1,
            now = clock(),
        )
        dao.markOutboxFailed(
            messageId = message.messageId,
            ownerUserId = message.ownerUserId,
            expectedState = expectedState,
            retryState = when (decision.target) {
                OutboxRetryPolicy.RetryTarget.RE_ENCRYPT -> MessagingV2OutboxState.RETRY_PREPARE
                OutboxRetryPolicy.RetryTarget.RESEND -> MessagingV2OutboxState.RETRY_SEND
            },
            errorCode = decision.errorCode,
            nextAttemptAt = decision.nextAttemptAt,
            now = clock(),
        )
    }

    // 刷写常量见 :domain:messaging 的 OutboxFlushLimits。
}
