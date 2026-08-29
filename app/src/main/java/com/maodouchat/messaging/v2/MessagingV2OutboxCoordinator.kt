package com.maodouchat.messaging.v2

import com.maodouchat.data.local.dao.MessagingV2Dao
import com.maodouchat.data.local.entity.MessagingV2OutboxEntity
import com.maodouchat.data.local.entity.MessagingV2OutboxState
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiService
import com.maodouchat.network.EncryptedDeviceEnvelopeRequestV2
import com.maodouchat.network.SendMessageRequestV2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class PreparedMessageV2(
    val groupRevision: Long?,
    val attachmentIds: List<String> = emptyList(),
    val envelopes: List<EncryptedDeviceEnvelopeRequestV2>,
)

fun interface MessagingV2EnvelopePreparer {
    /** Resolves the current membership/device snapshot and encrypts for every destination device. */
    suspend fun prepare(token: String, message: MessagingV2OutboxEntity): PreparedMessageV2
}

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
                staleBefore = now - STALE_CLAIM_MS,
                now = now,
            )
            repeat(MAX_MESSAGES_PER_RUN) {
                val claimed = dao.claimNextOutbox(ownerUserId, clock()) ?: return
                when (claimed.state) {
                    MessagingV2OutboxState.PREPARING -> prepare(token, claimed)
                    MessagingV2OutboxState.SENDING -> send(token, claimed)
                    else -> error("messaging_v2_invalid_claim_state:${claimed.state}")
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
                retryState = MessagingV2OutboxState.RETRY_PREPARE,
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
                    attachmentIds = messageAttachmentIds(message),
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
            if (
                error is ApiException &&
                error.serverCode == "GROUP_REVISION_MISMATCH" &&
                MessagingV2GroupControlPolicy.isGroupControl(message.kind)
            ) {
                check(
                    dao.discardOutbox(
                        message.messageId,
                        message.ownerUserId,
                        MessagingV2OutboxState.SENDING,
                    ) == 1,
                ) { "messaging_v2_stale_control_send_discard_lost" }
                return
            }
            val needsFreshEncryption = error is ApiException && error.serverCode in setOf(
                "DEVICE_COVERAGE_MISMATCH",
                "GROUP_REVISION_MISMATCH",
            )
            markFailure(
                message = message,
                expectedState = MessagingV2OutboxState.SENDING,
                retryState = if (needsFreshEncryption) {
                    MessagingV2OutboxState.RETRY_PREPARE
                } else {
                    MessagingV2OutboxState.RETRY_SEND
                },
                error = error,
            )
        }
    }

    private fun messageAttachmentIds(message: MessagingV2OutboxEntity): List<String> =
        runCatching {
            Json.decodeFromString<MessagingV2Content>(message.localPayload).attachmentIds
        }.getOrDefault(emptyList())

    private suspend fun markFailure(
        message: MessagingV2OutboxEntity,
        expectedState: String,
        retryState: String,
        error: Exception,
    ) {
        val attempts = message.attempts + 1
        val nextAttemptAt = if (error.message == SENDER_KEY_COVERAGE_PENDING) {
            // A Sender Key mailbox item was queued during preparation. Retry the data message
            // immediately after the current flush can send that item; exponential backoff here
            // needlessly stalls a perfectly valid group send.
            clock()
        } else {
            MessagingV2RetryPolicy.nextAttemptAt(clock(), attempts)
        }
        dao.markOutboxFailed(
            messageId = message.messageId,
            ownerUserId = message.ownerUserId,
            expectedState = expectedState,
            retryState = retryState,
            errorCode = error.message?.take(120)?.ifBlank { null } ?: error::class.java.simpleName,
            nextAttemptAt = nextAttemptAt,
            now = clock(),
        )
    }

    private companion object {
        const val MAX_MESSAGES_PER_RUN = 100
        const val STALE_CLAIM_MS = 2L * 60L * 1_000L
        const val SENDER_KEY_COVERAGE_PENDING = "sender_key_coverage_pending"
    }
}
