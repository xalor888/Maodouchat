package com.maodouchat.messaging.v2

import com.maodouchat.crypto.SignalProtocol
import com.maodouchat.crypto.SenderKeyDistOutcome
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import com.maodouchat.data.local.entity.MessagingV2OutboxEntity
import com.maodouchat.network.EncryptedDeviceEnvelopeRequestV2
import com.maodouchat.network.ApiService
import kotlinx.serialization.json.Json

data class MessagingV2DeviceTarget(val userId: String, val deviceId: Int)

data class MessagingV2ConversationSnapshot(
    val conversationId: String,
    val ownerUserId: String,
    val isGroup: Boolean,
    val memberRevision: Long,
    val participantUserIds: List<String>,
    /** Every confirmed destination device except the current sender device. */
    val targets: Set<MessagingV2DeviceTarget>,
)

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
        return if (snapshot.isGroup && message.kind in DIRECT_GROUP_CONTROL_KINDS) {
            if (snapshot.targets.isEmpty()) {
                return PreparedMessageV2(groupRevision = snapshot.memberRevision, envelopes = emptyList())
            }
            val targetUserIds = snapshot.targets.mapTo(linkedSetOf()) { it.userId }
            val content = decodeContent(message)
            val encrypted = signalProtocol.encryptMultiRecipientContentEnvelopeWithTargets(
                token = token,
                recipientIds = targetUserIds.toList(),
                plaintext = if (message.kind == KIND_SENDER_KEY) content.body else message.localPayload,
                payloadType = if (message.kind == KIND_SENDER_KEY) TYPE_SENDER_KEY else KIND_KEY_REQUEST,
                includeCurrentUserDevices = true,
                requiredRecipientIds = targetUserIds.filterNot { it == snapshot.ownerUserId }.toSet(),
            ).getOrThrow()
            val envelopes = encrypted.ciphertexts.map {
                EncryptedDeviceEnvelopeRequestV2(
                    recipientUserId = it.userId,
                    recipientDeviceId = it.deviceId,
                    ciphertextType = it.ciphertextType,
                    ciphertext = it.ciphertext,
                )
            }
            check(envelopes.mapTo(linkedSetOf()) {
                MessagingV2DeviceTarget(it.recipientUserId, it.recipientDeviceId)
            } == snapshot.targets) { "messaging_v2_group_control_coverage_mismatch" }
            PreparedMessageV2(
                groupRevision = snapshot.memberRevision,
                envelopes = envelopes,
            )
        } else if (snapshot.isGroup) {
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
                attachmentIds = decodeContent(message).attachmentIds,
                envelopes = snapshot.targets.sortedWith(compareBy({ it.userId }, { it.deviceId })).map {
                    EncryptedDeviceEnvelopeRequestV2(
                        recipientUserId = it.userId,
                        recipientDeviceId = it.deviceId,
                        ciphertextType = CIPHERTEXT_SENDER_KEY,
                        ciphertext = senderKeyEnvelope,
                    )
                },
            )
        } else {
            if (snapshot.targets.isEmpty()) {
                return PreparedMessageV2(
                    groupRevision = null,
                    attachmentIds = decodeContent(message).attachmentIds,
                    envelopes = emptyList(),
                )
            }
            val targetUserIds = snapshot.targets.mapTo(linkedSetOf()) { it.userId }
            val encrypted = signalProtocol.encryptMultiRecipientContentEnvelopeWithTargets(
                token = token,
                recipientIds = targetUserIds.toList(),
                plaintext = message.localPayload,
                payloadType = message.kind,
                includeCurrentUserDevices = true,
                requiredRecipientIds = targetUserIds.filterNot { it == snapshot.ownerUserId }.toSet(),
            ).getOrThrow().ciphertexts
            val envelopes = encrypted.map {
                EncryptedDeviceEnvelopeRequestV2(
                    recipientUserId = it.userId,
                    recipientDeviceId = it.deviceId,
                    ciphertextType = it.ciphertextType,
                    ciphertext = it.ciphertext,
                )
            }
            val actualTargets = envelopes.mapTo(linkedSetOf()) {
                MessagingV2DeviceTarget(it.recipientUserId, it.recipientDeviceId)
            }
            check(actualTargets == snapshot.targets) { "messaging_v2_crypto_coverage_mismatch" }
            PreparedMessageV2(groupRevision = null, attachmentIds = decodeContent(message).attachmentIds, envelopes = envelopes)
        }
    }

    private fun decodeContent(message: MessagingV2OutboxEntity): MessagingV2Content =
        Json { ignoreUnknownKeys = false }.decodeFromString(message.localPayload)

    private companion object {
        const val CIPHERTEXT_SENDER_KEY = "SENDER_KEY"
        const val KIND_SENDER_KEY = "SENDER_KEY"
        const val KIND_KEY_REQUEST = "KEY_REQUEST"
        const val TYPE_SENDER_KEY = "SK_DIST"
        val DIRECT_GROUP_CONTROL_KINDS = setOf(KIND_SENDER_KEY, KIND_KEY_REQUEST)
    }
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
    private val json = Json { ignoreUnknownKeys = false }

    override suspend fun process(envelope: MessagingV2InboxEntity) {
        if (envelope.kind == KIND_SERVICE) {
            if (!MessagingV2ServiceEnvelopePolicy.accepts(
                    senderUserId = envelope.senderUserId,
                    senderDeviceId = envelope.senderDeviceId,
                    ciphertextType = envelope.ciphertextType,
                )
            ) return
            val content = runCatching {
                json.decodeFromString<MessagingV2Content>(envelope.ciphertext)
            }.getOrNull() ?: return
            if (!MessagingV2ContentPolicy.accepts(envelope.kind, content)) return
            domainSink.commit(envelope, content)
            return
        }
        val decrypted = if (envelope.ciphertextType == CIPHERTEXT_SENDER_KEY) {
            signalProtocol.decryptGroupContentEnvelope(
                senderId = envelope.senderUserId,
                content = envelope.ciphertext,
                expectedGroupId = envelope.conversationId,
                currentEpoch = groupRevisionProvider(envelope.conversationId),
            )
        } else {
            signalProtocol.decryptDeviceCiphertext(
                senderId = envelope.senderUserId,
                senderDeviceId = envelope.senderDeviceId,
                ciphertextType = envelope.ciphertextType,
                ciphertext = envelope.ciphertext,
            )
        }
        when (decrypted) {
            is SignalProtocol.DecryptResult.Success -> {
                if (envelope.kind == KIND_SENDER_KEY) {
                    when (
                        signalProtocol.processSenderKeyDistributionEnvelope(
                            senderId = envelope.senderUserId,
                            content = decrypted.plaintext,
                            expectedGroupId = envelope.conversationId,
                            currentEpoch = groupRevisionProvider(envelope.conversationId),
                        )
                    ) {
                        SenderKeyDistOutcome.Installed,
                        SenderKeyDistOutcome.Skipped -> Unit
                        SenderKeyDistOutcome.Failed -> error("messaging_v2_sender_key_install_failed")
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
                    val content = runCatching {
                        json.decodeFromString<MessagingV2Content>(decrypted.plaintext)
                    }.getOrNull() ?: return
                    // The sender controls `kind`; binding it after authenticated decryption
                    // prevents DATA disguised as RECEIPT and ACKs poison payloads so they cannot
                    // permanently block the ordered inbox.
                    if (!MessagingV2ContentPolicy.accepts(envelope.kind, content)) return
                    domainSink.commit(envelope, content)
                }
            }
            SignalProtocol.DecryptResult.Duplicate -> {
                // Sender-key installation is idempotent: a replayed distribution after a crash
                // is already installed and must be acknowledged instead of dead-lettered.
                if (envelope.kind == KIND_SENDER_KEY) return
                // The ratchet step survived a previous attempt. Recover the projection from the
                // journal written before the original commit; otherwise acknowledge only when
                // the message verifiably reached the timeline. Anything else is retried into
                // the dead-letter path instead of silently losing the body.
                val journaled = inboxDao?.plaintextJournal(envelope.envelopeId)?.takeIf(String::isNotBlank)
                if (journaled == null) {
                    if (inboxDao?.isMessageProjected(envelope.ownerUserId, envelope.messageId) != true) {
                        error("messaging_v2_duplicate_uncommitted")
                    }
                } else {
                    val content = runCatching {
                        json.decodeFromString<MessagingV2Content>(journaled)
                    }.getOrNull()
                    if (content != null && MessagingV2ContentPolicy.accepts(envelope.kind, content)) {
                        domainSink.commit(envelope, content)
                    }
                    // Intentional skips (policy-rejected) and unrecoverable payloads are
                    // acknowledged with the journal cleared so they cannot replay forever.
                    inboxDao.writePlaintextJournal(envelope.envelopeId, "", System.currentTimeMillis())
                }
            }
            SignalProtocol.DecryptResult.NoSession -> {
                if (envelope.ciphertextType == CIPHERTEXT_SENDER_KEY) {
                    val epoch = envelope.groupRevision
                        ?: groupRevisionProvider(envelope.conversationId)
                        ?: 0L
                    if (epoch > 0L) onSenderKeyMissing(envelope, epoch)
                }
                error("messaging_v2_no_session")
            }
            SignalProtocol.DecryptResult.UntrustedIdentity -> error("messaging_v2_untrusted_identity")
            SignalProtocol.DecryptResult.FutureEpoch -> error("messaging_v2_future_group_revision")
            SignalProtocol.DecryptResult.NotForThisDevice -> error("messaging_v2_wrong_device")
            SignalProtocol.DecryptResult.UnsupportedEnvelope -> error("messaging_v2_unsupported_ciphertext")
            SignalProtocol.DecryptResult.Failed -> error("messaging_v2_decrypt_failed")
        }
    }

    private companion object {
        const val CIPHERTEXT_SENDER_KEY = "SENDER_KEY"
        const val KIND_SENDER_KEY = "SENDER_KEY"
        const val KIND_SERVICE = "SERVICE"
    }
}
