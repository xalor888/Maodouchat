package com.maodouchat.messaging.v2

import com.maodouchat.core.crypto.DecryptResult
import com.maodouchat.core.model.MessagingV2Content
import com.maodouchat.core.model.MessagingV2ConversationSnapshot
import com.maodouchat.core.model.MessagingV2DeviceTarget
import com.maodouchat.core.model.MessagingV2GroupControlPolicy
import com.maodouchat.core.model.MessagingV2StaleGroupControlException
import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import com.maodouchat.data.local.entity.MessagingV2OutboxEntity
import com.maodouchat.domain.messaging.DecryptPath
import com.maodouchat.domain.messaging.DuplicateEnvelopeRecovery
import com.maodouchat.domain.messaging.EnvelopeKind
import com.maodouchat.domain.messaging.EnvelopePrepareRoute
import com.maodouchat.domain.messaging.OutboxWireFormat
import com.maodouchat.domain.messaging.PreparedMessageV2
import com.maodouchat.domain.messaging.SENDER_KEY_INSTALL_FAILURE_MESSAGE
import com.maodouchat.domain.messaging.decideDecryptPath
import com.maodouchat.domain.messaging.decideDuplicateRecovery
import com.maodouchat.domain.messaging.decideEnvelopePrepareRoute
import com.maodouchat.domain.messaging.decideGroupControlPayload
import com.maodouchat.domain.messaging.decryptFailureMessage
import com.maodouchat.domain.messaging.decryptedEnvelopeContent
import com.maodouchat.domain.messaging.deviceEnvelopes
import com.maodouchat.domain.messaging.envelopeTargetsCovered
import com.maodouchat.domain.messaging.isSenderKeyInstallFailure
import com.maodouchat.domain.messaging.outboxAttachmentIds
import com.maodouchat.domain.messaging.requiredRecipientIds
import com.maodouchat.domain.messaging.resolveSenderKeyMissingEpoch
import com.maodouchat.domain.messaging.senderKeyEnvelopes
import com.maodouchat.domain.messaging.serviceEnvelopeContent
import com.maodouchat.domain.messaging.targetUserIds
import com.maodouchat.network.ApiService
import kotlinx.serialization.json.Json

fun interface MessagingV2ConversationSnapshotProvider {
    suspend fun get(token: String, conversationId: String): MessagingV2ConversationSnapshot
}

class ApiMessagingV2ConversationSnapshotProvider(
    private val ownerUserId: () -> String,
) : MessagingV2ConversationSnapshotProvider {
    override suspend fun get(token: String, conversationId: String): MessagingV2ConversationSnapshot {
        val owner = ownerUserId().takeIf(String::isNotBlank)
            ?: error("messaging_v2_owner_missing")
        val snapshot = ApiService.getConversationSnapshotV2(token, conversationId).getOrThrow()
        return MessagingV2ConversationSnapshot(
            conversationId = snapshot.conversationId,
            ownerUserId = owner,
            isGroup = snapshot.isGroup,
            memberRevision = snapshot.memberRevision,
            participantUserIds = snapshot.participantUserIds,
            targets = snapshot.targets.mapTo(linkedSetOf()) {
                MessagingV2DeviceTarget(it.userId, it.deviceId)
            },
        )
    }
}

class SignalMessagingV2EnvelopePreparer(
    private val signalProtocol: SignalProtocol,
    private val snapshotProvider: MessagingV2ConversationSnapshotProvider,
    private val ensureGroupReady: suspend (groupId: String, epoch: Long) -> Unit = { _, _ -> },
) : MessagingV2EnvelopePreparer {
    // Json 线程安全，每次发消息都 new 会重复走构建开销，实例级复用（本类由 MessagingV2Runtime 懒加载单例持有）。
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun prepare(token: String, message: MessagingV2OutboxEntity): PreparedMessageV2 {
        val snapshot = snapshotProvider.get(token, message.conversationId)
        require(snapshot.conversationId == message.conversationId) { "messaging_v2_snapshot_mismatch" }
        require(snapshot.ownerUserId == message.ownerUserId) { "messaging_v2_snapshot_owner_mismatch" }
        if (
            snapshot.isGroup &&
            MessagingV2GroupControlPolicy.isStale(
                message.kind,
                message.groupRevision,
                snapshot.memberRevision,
            )
        ) {
            throw MessagingV2StaleGroupControlException()
        }
        return when (
            decideEnvelopePrepareRoute(
                snapshot.isGroup,
                MessagingV2GroupControlPolicy.isGroupControl(message.kind),
            )
        ) {
            EnvelopePrepareRoute.GROUP_CONTROL -> {
                if (snapshot.targets.isEmpty()) {
                    return PreparedMessageV2(groupRevision = snapshot.memberRevision, envelopes = emptyList())
                }
                val recipientUserIds = targetUserIds(snapshot.targets)
                val content = decodeContent(message)
                val payload = decideGroupControlPayload(message.kind)
                val encrypted = signalProtocol.encryptMultiRecipientContentEnvelopeWithTargets(
                    token = token,
                    recipientIds = recipientUserIds.toList(),
                    plaintext = if (payload.useDecodedBody) content.body else message.localPayload,
                    payloadType = payload.payloadType,
                    includeCurrentUserDevices = true,
                    requiredRecipientIds = requiredRecipientIds(recipientUserIds, snapshot.ownerUserId),
                ).getOrThrow()
                val envelopes = deviceEnvelopes(encrypted.ciphertexts)
                check(envelopeTargetsCovered(envelopes, snapshot.targets)) { "messaging_v2_group_control_coverage_mismatch" }
                PreparedMessageV2(
                    groupRevision = snapshot.memberRevision,
                    envelopes = envelopes,
                )
            }
            EnvelopePrepareRoute.GROUP_SENDER_KEY -> {
                if (snapshot.targets.isNotEmpty()) {
                    ensureGroupReady(snapshot.conversationId, snapshot.memberRevision)
                }
                val senderKeyEnvelope = signalProtocol.encryptGroupContentEnvelope(
                    groupId = snapshot.conversationId,
                    plaintext = message.localPayload,
                    payloadType = message.kind,
                    epoch = snapshot.memberRevision,
                ).getOrThrow()
                PreparedMessageV2(
                    groupRevision = snapshot.memberRevision,
                    attachmentIds = outboxAttachmentIds(message.localPayload),
                    envelopes = senderKeyEnvelopes(snapshot.targets, senderKeyEnvelope),
                )
            }
            EnvelopePrepareRoute.DIRECT -> {
                if (snapshot.targets.isEmpty()) {
                    return PreparedMessageV2(
                        groupRevision = null,
                        attachmentIds = outboxAttachmentIds(message.localPayload),
                        envelopes = emptyList(),
                    )
                }
                val recipientUserIds = targetUserIds(snapshot.targets)
                val encrypted = signalProtocol.encryptMultiRecipientContentEnvelopeWithTargets(
                    token = token,
                    recipientIds = recipientUserIds.toList(),
                    plaintext = message.localPayload,
                    payloadType = message.kind,
                    includeCurrentUserDevices = true,
                    requiredRecipientIds = requiredRecipientIds(recipientUserIds, snapshot.ownerUserId),
                ).getOrThrow().ciphertexts
                val envelopes = deviceEnvelopes(encrypted)
                check(envelopeTargetsCovered(envelopes, snapshot.targets)) { "messaging_v2_crypto_coverage_mismatch" }
                PreparedMessageV2(groupRevision = null, attachmentIds = outboxAttachmentIds(message.localPayload), envelopes = envelopes)
            }
        }
    }

    // Retained for the E2E test; the rule itself lives in OutboxWireFormat.
    internal fun wireCiphertextType(value: String): String = OutboxWireFormat.ciphertextType(value)

    private fun decodeContent(message: MessagingV2OutboxEntity): MessagingV2Content =
        json.decodeFromString(message.localPayload)
}

fun interface MessagingV2DomainSink {
    /** Must be an idempotent local transaction keyed by envelope.messageId. */
    suspend fun commit(envelope: MessagingV2InboxEntity, content: MessagingV2Content)
}

class SignalMessagingV2EnvelopeProcessor(
    private val signalProtocol: SignalProtocol,
    private val domainSink: MessagingV2DomainSink,
    private val groupRevisionProvider: suspend (String) -> Long?,
    private val onSenderKeyMissing: suspend (MessagingV2InboxEntity, Long) -> Unit = { _, _ -> },
    private val inboxDao: com.maodouchat.data.local.dao.MessagingV2Dao? = null,
) : MessagingV2EnvelopeProcessor {
    override suspend fun process(envelope: MessagingV2InboxEntity) {
        if (envelope.kind == EnvelopeKind.SERVICE) {
            serviceEnvelopeContent(
                senderUserId = envelope.senderUserId,
                senderDeviceId = envelope.senderDeviceId,
                ciphertextType = envelope.ciphertextType,
                kind = envelope.kind,
                ciphertext = envelope.ciphertext,
            )?.let { domainSink.commit(envelope, it) }
            return
        }
        val decrypted = when (decideDecryptPath(envelope.ciphertextType)) {
            DecryptPath.GROUP -> signalProtocol.decryptGroupContentEnvelope(
                senderId = envelope.senderUserId,
                content = envelope.ciphertext,
                expectedGroupId = envelope.conversationId,
                currentEpoch = groupRevisionProvider(envelope.conversationId),
            )
            DecryptPath.DEVICE -> signalProtocol.decryptDeviceCiphertext(
                senderId = envelope.senderUserId,
                senderDeviceId = envelope.senderDeviceId,
                ciphertextType = envelope.ciphertextType,
                ciphertext = envelope.ciphertext,
            )
        }
        when (decrypted) {
            is DecryptResult.Success -> {
                if (envelope.kind == EnvelopeKind.SENDER_KEY) {
                    if (
                        isSenderKeyInstallFailure(
                            signalProtocol.processSenderKeyDistributionEnvelope(
                                senderId = envelope.senderUserId,
                                content = decrypted.plaintext,
                                expectedGroupId = envelope.conversationId,
                                currentEpoch = groupRevisionProvider(envelope.conversationId),
                            ),
                        )
                    ) {
                        error(SENDER_KEY_INSTALL_FAILURE_MESSAGE)
                    }
                } else {
                    // Journal before parsing/commit: the ratchet step is already persisted at
                    // this point, so the journal is the only recovery path when the process
                    // dies before the projection commits.
                    inboxDao?.writePlaintextJournal(
                        envelopeId = envelope.envelopeId,
                        plaintext = decrypted.plaintext,
                        now = System.currentTimeMillis(),
                    )
                    // The sender controls `kind`; binding it after authenticated decryption
                    // prevents DATA disguised as RECEIPT and ACKs poison payloads so they cannot
                    // permanently block the ordered inbox.
                    decryptedEnvelopeContent(envelope.kind, decrypted.plaintext)?.let {
                        domainSink.commit(envelope, it)
                    }
                }
            }
            DecryptResult.Duplicate -> {
                // Sender-key installation is idempotent: a replayed distribution after a crash
                // is already installed and must be acknowledged instead of dead-lettered.
                if (envelope.kind == EnvelopeKind.SENDER_KEY) return
                // The ratchet step survived a previous attempt. Recover the projection from the
                // journal written before the original commit; otherwise acknowledge only when
                // the message verifiably reached the timeline. Anything else is retried into
                // the dead-letter path instead of silently losing the body.
                val journaled = inboxDao?.plaintextJournal(envelope.envelopeId)
                when (val recovery = decideDuplicateRecovery(envelope.kind, journaled) {
                    inboxDao?.isMessageProjected(envelope.ownerUserId, envelope.messageId) == true
                }) {
                    DuplicateEnvelopeRecovery.Acknowledge -> Unit
                    DuplicateEnvelopeRecovery.Uncommitted -> error("messaging_v2_duplicate_uncommitted")
                    is DuplicateEnvelopeRecovery.Recover -> {
                        domainSink.commit(envelope, recovery.content)
                        inboxDao?.writePlaintextJournal(envelope.envelopeId, "", System.currentTimeMillis())
                    }
                    // Intentional skips (policy-rejected) and unrecoverable payloads are
                    // acknowledged with the journal cleared so they cannot replay forever.
                    DuplicateEnvelopeRecovery.DiscardJournal ->
                        inboxDao?.writePlaintextJournal(envelope.envelopeId, "", System.currentTimeMillis())
                }
            }
            DecryptResult.NoSession -> {
                resolveSenderKeyMissingEpoch(
                    ciphertextType = envelope.ciphertextType,
                    envelopeGroupRevision = envelope.groupRevision,
                    providedRevision = { groupRevisionProvider(envelope.conversationId) },
                )?.let { onSenderKeyMissing(envelope, it) }
                error(decryptFailureMessage(decrypted))
            }
            DecryptResult.UntrustedIdentity -> error(decryptFailureMessage(decrypted))
            DecryptResult.FutureEpoch -> error(decryptFailureMessage(decrypted))
            DecryptResult.NotForThisDevice -> error(decryptFailureMessage(decrypted))
            DecryptResult.UnsupportedEnvelope -> error(decryptFailureMessage(decrypted))
            DecryptResult.Failed -> error(decryptFailureMessage(decrypted))
        }
    }
}
