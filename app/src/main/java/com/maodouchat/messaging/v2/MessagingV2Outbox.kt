package com.maodouchat.messaging.v2

import androidx.room.withTransaction
import com.maodouchat.data.local.AppDatabase
import com.maodouchat.data.local.dao.MessagingV2Dao
import com.maodouchat.data.local.entity.MessageMutationTombstoneEntity
import com.maodouchat.data.local.entity.MessageMutationTombstoneKind
import com.maodouchat.data.local.entity.MessagingV2InboxEntity
import com.maodouchat.data.local.entity.MessagingV2OutboxEntity
import com.maodouchat.data.local.entity.MessagingV2OutboxState
import com.maodouchat.data.model.MessageStatus
import com.maodouchat.data.model.MessageType
import com.maodouchat.util.MediaCache
import kotlinx.serialization.json.Json
import java.util.UUID

/** The only application API allowed to create or retry durable v2 outbound commands. */
class MessagingV2Outbox(
    private val database: AppDatabase,
    private val dao: MessagingV2Dao,
    private val ownerUserId: () -> String,
    private val deviceId: () -> Int,
    private val wakeTransport: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    suspend fun enqueueText(
        conversationId: String,
        body: String,
        type: String = "TEXT",
        groupRevision: Long? = null,
        messageId: String = UUID.randomUUID().toString(),
    ): String = enqueueContentPayload(
        conversationId = conversationId,
        content = MessagingV2Content(type = type, body = body),
        groupRevision = groupRevision,
        kind = "DATA",
        messageId = messageId,
    )

    suspend fun enqueueContent(
        conversationId: String,
        body: String,
        type: MessageType,
        groupRevision: Long? = null,
        messageId: String = UUID.randomUUID().toString(),
    ): String = enqueueContentPayload(
        conversationId = conversationId,
        content = MessagingV2Content(type = type.name, body = body),
        groupRevision = groupRevision,
        kind = "DATA",
        messageId = messageId,
    )

    suspend fun retryContent(
        conversationId: String,
        body: String,
        type: MessageType,
        groupRevision: Long? = null,
        messageId: String,
    ) {
        val owner = requireOwner()
        if (dao.isMessageTerminal(owner, messageId)) return
        val existing = dao.getOutbox(messageId, owner)
        if (existing == null) {
            enqueueContent(conversationId, body, type, groupRevision, messageId)
            return
        }
        require(existing.conversationId == conversationId) { "messaging_v2_retry_conversation_mismatch" }
        require(existing.kind == "DATA") { "messaging_v2_retry_kind_mismatch" }
        dao.retryOutbox(messageId, owner, clock())
        wakeTransport()
    }

    internal suspend fun retryContentInCurrentTransaction(
        conversationId: String,
        body: String,
        type: MessageType,
        groupRevision: Long?,
        messageId: String,
    ) {
        val owner = requireOwner()
        val existing = dao.getOutbox(messageId, owner)
        if (existing == null) {
            enqueueContentInCurrentTransaction(conversationId, body, type, groupRevision, messageId)
            return
        }
        require(existing.conversationId == conversationId) { "messaging_v2_retry_conversation_mismatch" }
        require(existing.kind == "DATA") { "messaging_v2_retry_kind_mismatch" }
        dao.retryOutbox(messageId, owner, clock())
    }

    suspend fun enqueueEvent(
        conversationId: String,
        event: MessagingV2Event,
        groupRevision: Long? = null,
        messageId: String = UUID.randomUUID().toString(),
        kind: String = "EVENT",
    ): String {
        val terminalKind = when (event.action) {
            MessagingV2EventAction.DELETE -> MessageMutationTombstoneKind.DELETE
            MessagingV2EventAction.REVOKE -> MessageMutationTombstoneKind.REVOKE
            else -> null
        }
        return enqueueContentPayload(
            conversationId = conversationId,
            content = MessagingV2Content(type = "EVENT", event = event),
            groupRevision = groupRevision,
            kind = kind,
            messageId = messageId,
            terminalTargetMessageId = event.targetMessageId.takeIf { terminalKind != null },
            terminalKind = terminalKind,
        )
    }

    suspend fun enqueueReadReceipt(
        conversationId: String,
        throughMessageId: String,
        groupRevision: Long? = null,
    ): String {
        val owner = requireOwner()
        return enqueueEvent(
            conversationId = conversationId,
            event = MessagingV2Event(
                action = MessagingV2EventAction.READ_RECEIPT,
                targetMessageId = throughMessageId,
                status = MessageStatus.READ.name,
                throughMessageId = throughMessageId,
            ),
            groupRevision = groupRevision,
            messageId = receiptMessageId(
                action = MessagingV2EventAction.READ_RECEIPT,
                targetMessageId = throughMessageId,
                owner = owner,
            ),
            kind = "RECEIPT",
        )
    }

    suspend fun enqueueAttachmentReference(
        conversationId: String,
        type: MessageType,
        referencePayload: String,
        groupRevision: Long? = null,
        messageId: String = UUID.randomUUID().toString(),
    ): String {
        require(type in ATTACHMENT_TYPES) { "messaging_v2_attachment_type_invalid" }
        return enqueueContentPayload(
            conversationId = conversationId,
            content = MessagingV2Content(
                type = type.name,
                body = referencePayload,
                attachmentIds = listOfNotNull(
                    MediaCache.decodeEncryptedAttachmentReference(referencePayload)?.attachmentId,
                ),
            ),
            groupRevision = groupRevision,
            kind = "DATA",
            messageId = messageId,
        )
    }

    suspend fun enqueueSenderKeyDistribution(
        conversationId: String,
        distributionEnvelope: String,
        groupRevision: Long,
        messageId: String = UUID.randomUUID().toString(),
    ): String = enqueueContentPayload(
        conversationId = conversationId,
        content = MessagingV2Content(type = MessageType.SK_DIST.name, body = distributionEnvelope),
        groupRevision = groupRevision,
        kind = "SENDER_KEY",
        messageId = messageId,
    )

    suspend fun enqueueSenderKeyRequest(
        conversationId: String,
        requestedSenderUserId: String,
        groupRevision: Long,
        failedMessageId: String,
    ): String {
        require(requestedSenderUserId.isNotBlank()) { "messaging_v2_key_request_sender_missing" }
        require(groupRevision > 0L) { "messaging_v2_key_request_revision_missing" }
        require(failedMessageId.isNotBlank()) { "messaging_v2_key_request_message_missing" }
        val owner = requireOwner()
        val seed = "$conversationId|$requestedSenderUserId|$groupRevision|$failedMessageId|$owner|${deviceId()}"
        return enqueuePayload(
            owner = owner,
            conversationId = conversationId,
            payload = json.encodeToString(
                MessagingV2Content.serializer(),
                MessagingV2Content(
                    type = TYPE_SENDER_KEY_REQUEST,
                    attributes = mapOf(
                        ATTRIBUTE_REQUESTED_SENDER to requestedSenderUserId,
                        ATTRIBUTE_FAILED_MESSAGE_ID to failedMessageId,
                    ),
                ),
            ),
            clientTimestamp = clock(),
            groupRevision = groupRevision,
            kind = KIND_KEY_REQUEST,
            messageId = "kr_${UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8))}",
        )
    }

    internal suspend fun enqueueDeliveryReceipt(envelope: MessagingV2InboxEntity) {
        val owner = ownerUserId().takeIf(String::isNotBlank) ?: return
        enqueueEvent(
            conversationId = envelope.conversationId,
            event = MessagingV2Event(
                action = MessagingV2EventAction.DELIVERY_RECEIPT,
                targetMessageId = envelope.messageId,
                status = MessageStatus.DELIVERED.name,
            ),
            groupRevision = envelope.groupRevision,
            messageId = receiptMessageId(
                action = MessagingV2EventAction.DELIVERY_RECEIPT,
                targetMessageId = envelope.messageId,
                owner = owner,
            ),
            kind = "RECEIPT",
        )
    }

    private suspend fun enqueueContentPayload(
        conversationId: String,
        content: MessagingV2Content,
        groupRevision: Long?,
        kind: String,
        messageId: String,
        terminalTargetMessageId: String? = null,
        terminalKind: String? = null,
    ): String {
        val owner = requireOwner()
        val payload = json.encodeToString(MessagingV2Content.serializer(), content)
        val timestamp = clock()
        val result = database.withTransaction {
            terminalTargetMessageId?.let { targetMessageId ->
                dao.upsertMessageTombstone(
                    MessageMutationTombstoneEntity(
                        ownerUserId = owner,
                        messageId = targetMessageId,
                        conversationId = conversationId,
                        kind = requireNotNull(terminalKind),
                        terminalAt = timestamp,
                    ),
                )
            }
            enqueuePayloadInCurrentTransaction(
                owner = owner,
                conversationId = conversationId,
                payload = payload,
                clientTimestamp = timestamp,
                groupRevision = groupRevision,
                kind = kind,
                messageId = messageId,
            )
        }
        wakeTransport()
        return result
    }

    internal suspend fun retryContentPayloadInCurrentTransaction(
        conversationId: String,
        payload: ContentPayload,
        groupRevision: Long?,
        messageId: String,
    ) {
        val owner = requireOwner()
        val existing = dao.getOutbox(messageId, owner)
        if (existing == null) {
            enqueueContentPayloadInCurrentTransaction(conversationId, payload, groupRevision, messageId)
            return
        }
        require(existing.conversationId == conversationId) { "messaging_v2_retry_conversation_mismatch" }
        require(existing.kind == "DATA") { "messaging_v2_retry_kind_mismatch" }
        dao.retryOutbox(messageId, owner, clock())
    }

    internal suspend fun enqueueContentInCurrentTransaction(
        conversationId: String,
        body: String,
        type: MessageType,
        groupRevision: Long?,
        messageId: String,
    ): String = enqueuePayloadInCurrentTransaction(
        owner = requireOwner(),
        conversationId = conversationId,
        payload = json.encodeToString(
            MessagingV2Content.serializer(),
            MessagingV2Content(type = type.name, body = body),
        ),
        clientTimestamp = clock(),
        groupRevision = groupRevision,
        kind = "DATA",
        messageId = messageId,
    )

    internal suspend fun enqueueContentPayloadInCurrentTransaction(
        conversationId: String,
        payload: ContentPayload,
        groupRevision: Long?,
        messageId: String,
    ): String = enqueuePayloadInCurrentTransaction(
        owner = requireOwner(),
        conversationId = conversationId,
        payload = json.encodeToString(
            MessagingV2Content.serializer(),
            ContentPayloadCodec.encode(payload),
        ),
        clientTimestamp = clock(),
        groupRevision = groupRevision,
        kind = "DATA",
        messageId = messageId,
    )

    internal fun wakeAfterCommit() = wakeTransport()

    private suspend fun enqueuePayload(
        owner: String,
        conversationId: String,
        payload: String,
        clientTimestamp: Long,
        groupRevision: Long?,
        kind: String,
        messageId: String,
    ): String {
        val result = database.withTransaction {
            enqueuePayloadInCurrentTransaction(
                owner = owner,
                conversationId = conversationId,
                payload = payload,
                clientTimestamp = clientTimestamp,
                groupRevision = groupRevision,
                kind = kind,
                messageId = messageId,
            )
        }
        wakeTransport()
        return result
    }

    private suspend fun enqueuePayloadInCurrentTransaction(
        owner: String,
        conversationId: String,
        payload: String,
        clientTimestamp: Long,
        groupRevision: Long?,
        kind: String,
        messageId: String,
    ): String {
        dao.getOutbox(messageId, owner)?.let { existing ->
            require(
                existing.conversationId == conversationId &&
                    existing.kind == kind &&
                    existing.localPayload == payload,
            ) { "messaging_v2_outbox_id_conflict" }
            return messageId
        }
        dao.enqueueOutbox(
            MessagingV2OutboxEntity(
                messageId = messageId,
                ownerUserId = owner,
                conversationId = conversationId,
                kind = kind,
                localPayload = payload,
                clientTimestamp = clientTimestamp,
                groupRevision = groupRevision,
                state = MessagingV2OutboxState.QUEUED,
            ),
        )
        return messageId
    }

    internal fun currentOwnerUserId(): String = requireOwner()

    private fun receiptMessageId(action: String, targetMessageId: String, owner: String): String {
        val seed = "$action|$targetMessageId|$owner|${deviceId()}"
        return "r_${UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8))}"
    }

    private fun requireOwner(): String =
        ownerUserId().takeIf(String::isNotBlank) ?: error("messaging_v2_owner_missing")

    private companion object {
        const val KIND_KEY_REQUEST = "KEY_REQUEST"
        const val TYPE_SENDER_KEY_REQUEST = "SENDER_KEY_REQUEST"
        const val ATTRIBUTE_REQUESTED_SENDER = "requestedSenderUserId"
        const val ATTRIBUTE_FAILED_MESSAGE_ID = "failedMessageId"
        val ATTACHMENT_TYPES = setOf(
            MessageType.IMAGE,
            MessageType.GIF,
            MessageType.VIDEO,
            MessageType.VOICE,
            MessageType.FILE,
        )
    }
}
