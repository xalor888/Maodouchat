package com.maodouchat.server.messaging.v2

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.db.MessagingV2Envelopes
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.PinnedMessages
import com.maodouchat.server.db.ServiceMessageReactions
import com.maodouchat.server.db.ServiceMessages
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.db.StarMessages
import com.maodouchat.server.db.Users
import com.maodouchat.server.model.MessageResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

data class MessagingV2MessageMetadata(
    val id: String,
    val conversationId: String,
    val senderUserId: String,
    val recordClass: String,
)

data class MessagingV2ModerationDeleteResult(
    val metadata: MessagingV2MessageMetadata,
    val deletedAttachmentIds: List<String>,
)

class MessagingV2Repository(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val snapshotStore = ConversationDeviceSnapshotStore()
    private val admission = MessageAdmissionPolicy(snapshotStore, clock)
    private val json = Json { encodeDefaults = true }

    fun resolveAuthenticatedDevice(userId: String, authSessionId: String): Int? =
        snapshotStore.resolveAuthenticatedDevice(userId, authSessionId)

    fun conversationSnapshot(
        conversationId: String,
        requesterUserId: String,
        requesterDeviceId: Int,
    ): ConversationSnapshotV2Response =
        snapshotStore.conversationSnapshot(conversationId, requesterUserId, requesterDeviceId)

    fun send(
        command: SendMessageV2Command,
        admitNewMessage: () -> Boolean = { true },
    ): SendMessageV2Result = admission.send(command, admitNewMessage)





    /** Durable server-authored bot delivery. Bot content was already server-visible in v1. */
    fun enqueueServiceMessage(
        message: MessageResponse,
        recipientUserIds: Set<String>,
    ): SendMessageV2Result = transaction {
        enqueueServiceMessageInTransaction(message, recipientUserIds)
    }

    /**
     * Inserts the V2 transport record and device mailbox rows in the caller's
     * existing database transaction. Service content publishers use this to
     * make content visibility and mailbox delivery one atomic commitment.
     */
    internal fun enqueueServiceMessageInTransaction(
        message: MessageResponse,
        recipientUserIds: Set<String>,
    ): SendMessageV2Result {
        require(message.senderId.startsWith("bot_")) { "service_sender_not_bot" }
        val now = clock()
        val chat = Chats.selectAll()
            .where { Chats.id eq message.chatId }
            .forUpdate()
            .firstOrNull()
            ?: throw MessagingV2ConversationNotFoundException()
        val participantIds = ChatParticipants
            .select(ChatParticipants.userId)
            .where { ChatParticipants.chatId eq message.chatId }
            .mapTo(linkedSetOf()) { it[ChatParticipants.userId] }
        if (message.senderId !in participantIds) throw MessagingV2NotParticipantException()

        val serviceContent = json.encodeToString(
            ServiceMessagingV2Content(
                type = message.type,
                body = message.content,
            ),
        )
        val requestDigest = digestService(message, serviceContent)
        val existing = MessagingV2Messages.selectAll()
            .where { MessagingV2Messages.id eq message.id }
            .forUpdate()
            .firstOrNull()
        if (existing != null) {
            if (
                existing[MessagingV2Messages.senderUserId] != message.senderId ||
                existing[MessagingV2Messages.conversationId] != message.chatId ||
                existing[MessagingV2Messages.kind] != KIND_SERVICE ||
                existing[MessagingV2Messages.requestDigest] != requestDigest
            ) {
                throw MessagingV2DuplicateMessageException()
            }
            val envelopeRows = MessagingV2Envelopes.selectAll()
                .where { MessagingV2Envelopes.messageId eq message.id }
                .toList()
            return SendMessageV2Result(
                messageId = message.id,
                serverTimestamp = existing[MessagingV2Messages.serverTimestamp],
                envelopeCount = envelopeRows.size,
                idempotentReplay = true,
                recipientUserIds = envelopeRows.mapTo(linkedSetOf()) {
                    it[MessagingV2Envelopes.recipientUserId]
                },
            )
        }

        val eligibleRecipients = participantIds
            .filterTo(linkedSetOf()) { it != message.senderId && it in recipientUserIds }
        val targets = if (eligibleRecipients.isEmpty()) {
            emptyList()
        } else {
            SignalDevices.selectAll().where {
                (SignalDevices.userId inList eligibleRecipients) and
                    (SignalDevices.status eq "CONFIRMED")
            }.map {
                DeviceTarget(it[SignalDevices.userId], it[SignalDevices.deviceId])
            }
        }
        MessagingV2Messages.insert {
            it[id] = message.id
            it[conversationId] = message.chatId
            it[senderUserId] = message.senderId
            it[senderDeviceId] = SERVICE_DEVICE_ID
            it[kind] = KIND_SERVICE
            it[recordClass] = MessagingV2RecordClass.MESSAGE
            it[groupRevision] = chat[Chats.memberRevision].takeIf { chat[Chats.isGroup] }
            it[clientTimestamp] = message.timestamp
            it[serverTimestamp] = now
            it[MessagingV2Messages.requestDigest] = requestDigest
        }
        targets.forEach { target ->
            MessagingV2Envelopes.insert {
                it[id] = UUID.randomUUID().toString()
                it[messageId] = message.id
                it[recipientUserId] = target.userId
                it[recipientDeviceId] = target.deviceId
                it[ciphertextType] = CIPHERTEXT_SERVICE
                it[ciphertext] = serviceContent
                it[serverTimestamp] = now
                it[acknowledgedAt] = null
            }
        }
        return SendMessageV2Result(
            messageId = message.id,
            serverTimestamp = now,
            envelopeCount = targets.size,
            idempotentReplay = false,
            recipientUserIds = targets.mapTo(linkedSetOf()) { it.userId },
        )
    }

    /** Durable server-authored mutation for plaintext bot messages. */
    fun enqueueServiceEvent(
        id: String,
        conversationId: String,
        senderUserId: String,
        clientTimestamp: Long,
        event: ServiceMessagingV2Event,
        recipientUserIds: Set<String>,
    ): SendMessageV2Result = transaction {
        require(senderUserId.startsWith("bot_") || senderUserId == SYSTEM_SENDER_ID) {
            "service_sender_not_trusted"
        }
        require(event.targetMessageId.isNotBlank()) { "service_event_target_missing" }
        require(event.isValidServiceMutation()) { "service_event_invalid" }
        val now = clock()
        val chat = Chats.selectAll()
            .where { Chats.id eq conversationId }
            .forUpdate()
            .firstOrNull()
            ?: throw MessagingV2ConversationNotFoundException()
        val participantIds = ChatParticipants
            .select(ChatParticipants.userId)
            .where { ChatParticipants.chatId eq conversationId }
            .mapTo(linkedSetOf()) { it[ChatParticipants.userId] }
        if (senderUserId != SYSTEM_SENDER_ID && senderUserId !in participantIds) {
            throw MessagingV2NotParticipantException()
        }
        if (senderUserId == SYSTEM_SENDER_ID && event.action != ACTION_DELETE) {
            throw IllegalArgumentException("system_service_event_not_allowed")
        }

        val serviceContent = json.encodeToString(
            ServiceMessagingV2Content(
                type = TYPE_EVENT,
                event = event,
            ),
        )
        val requestDigest = digestServiceEvent(
            id = id,
            conversationId = conversationId,
            senderUserId = senderUserId,
            clientTimestamp = clientTimestamp,
            content = serviceContent,
        )
        val existing = MessagingV2Messages.selectAll()
            .where { MessagingV2Messages.id eq id }
            .forUpdate()
            .firstOrNull()
        if (existing != null) {
            if (
                existing[MessagingV2Messages.senderUserId] != senderUserId ||
                existing[MessagingV2Messages.conversationId] != conversationId ||
                existing[MessagingV2Messages.kind] != KIND_SERVICE ||
                existing[MessagingV2Messages.requestDigest] != requestDigest
            ) {
                throw MessagingV2DuplicateMessageException()
            }
            val envelopeRows = MessagingV2Envelopes.selectAll()
                .where { MessagingV2Envelopes.messageId eq id }
                .toList()
            return@transaction SendMessageV2Result(
                messageId = id,
                serverTimestamp = existing[MessagingV2Messages.serverTimestamp],
                envelopeCount = envelopeRows.size,
                idempotentReplay = true,
                recipientUserIds = envelopeRows.mapTo(linkedSetOf()) {
                    it[MessagingV2Envelopes.recipientUserId]
                },
            )
        }

        val eligibleRecipients = participantIds
            .filterTo(linkedSetOf()) { it != senderUserId && it in recipientUserIds }
        val targets = if (eligibleRecipients.isEmpty()) {
            emptyList()
        } else {
            SignalDevices.selectAll().where {
                (SignalDevices.userId inList eligibleRecipients) and
                    (SignalDevices.status eq "CONFIRMED")
            }.map {
                DeviceTarget(it[SignalDevices.userId], it[SignalDevices.deviceId])
            }
        }
        MessagingV2Messages.insert {
            it[MessagingV2Messages.id] = id
            it[MessagingV2Messages.conversationId] = conversationId
            it[MessagingV2Messages.senderUserId] = senderUserId
            it[senderDeviceId] = SERVICE_DEVICE_ID
            it[kind] = KIND_SERVICE
            it[recordClass] = MessagingV2RecordClass.EVENT
            it[groupRevision] = chat[Chats.memberRevision].takeIf { chat[Chats.isGroup] }
            it[MessagingV2Messages.clientTimestamp] = clientTimestamp
            it[serverTimestamp] = now
            it[MessagingV2Messages.requestDigest] = requestDigest
        }
        targets.forEach { target ->
            MessagingV2Envelopes.insert {
                it[MessagingV2Envelopes.id] = UUID.randomUUID().toString()
                it[messageId] = id
                it[recipientUserId] = target.userId
                it[recipientDeviceId] = target.deviceId
                it[ciphertextType] = CIPHERTEXT_SERVICE
                it[ciphertext] = serviceContent
                it[serverTimestamp] = now
                it[acknowledgedAt] = null
            }
        }
        SendMessageV2Result(
            messageId = id,
            serverTimestamp = now,
            envelopeCount = targets.size,
            idempotentReplay = false,
            recipientUserIds = targets.mapTo(linkedSetOf()) { it.userId },
        )
    }

    fun pending(userId: String, deviceId: Int, limit: Int): PendingInboxV2Response = transaction {
        val rows = (MessagingV2Envelopes innerJoin MessagingV2Messages)
            .selectAll()
            .where {
                (MessagingV2Envelopes.recipientUserId eq userId) and
                    (MessagingV2Envelopes.recipientDeviceId eq deviceId) and
                    MessagingV2Envelopes.acknowledgedAt.isNull()
            }
            .orderBy(MessagingV2Envelopes.sequence to SortOrder.ASC)
            .limit(limit + 1)
            .toList()
        PendingInboxV2Response(
            envelopes = rows.take(limit).map { row ->
                PendingEnvelopeV2(
                    envelopeId = row[MessagingV2Envelopes.id],
                    sequence = row[MessagingV2Envelopes.sequence],
                    messageId = row[MessagingV2Messages.id],
                    conversationId = row[MessagingV2Messages.conversationId],
                    senderUserId = row[MessagingV2Messages.senderUserId],
                    senderDeviceId = row[MessagingV2Messages.senderDeviceId],
                    kind = row[MessagingV2Messages.kind],
                    groupRevision = row[MessagingV2Messages.groupRevision],
                    clientTimestamp = row[MessagingV2Messages.clientTimestamp],
                    serverTimestamp = row[MessagingV2Messages.serverTimestamp],
                    ciphertextType = row[MessagingV2Envelopes.ciphertextType],
                    ciphertext = row[MessagingV2Envelopes.ciphertext],
                )
            },
            hasMore = rows.size > limit,
        )
    }

    fun acknowledge(userId: String, deviceId: Int, envelopeIds: Set<String>): Int {
        if (envelopeIds.isEmpty()) return 0
        val now = clock()
        return transaction {
            val ownedIds = MessagingV2Envelopes
                .select(MessagingV2Envelopes.id)
                .where {
                    (MessagingV2Envelopes.id inList envelopeIds) and
                        (MessagingV2Envelopes.recipientUserId eq userId) and
                        (MessagingV2Envelopes.recipientDeviceId eq deviceId)
                }
                .mapTo(linkedSetOf()) { it[MessagingV2Envelopes.id] }
            if (ownedIds.isEmpty()) return@transaction 0
            MessagingV2Envelopes.update({
                (MessagingV2Envelopes.id inList ownedIds) and
                    (MessagingV2Envelopes.recipientUserId eq userId) and
                    (MessagingV2Envelopes.recipientDeviceId eq deviceId) and
                    MessagingV2Envelopes.acknowledgedAt.isNull()
            }) {
                it[acknowledgedAt] = now
            }
            ownedIds.size
        }
    }

    fun messageMetadata(messageId: String): MessagingV2MessageMetadata? = transaction {
        MessagingV2Messages.selectAll()
            .where { MessagingV2Messages.id eq messageId }
            .firstOrNull()
            ?.toMetadata()
    }

    fun deleteMessageForModeration(messageId: String): MessagingV2ModerationDeleteResult? = transaction {
        val initial = MessagingV2Messages.selectAll()
            .where {
                (MessagingV2Messages.id eq messageId) and
                    (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
            }
            .firstOrNull()
            ?: return@transaction null
        val conversationId = initial[MessagingV2Messages.conversationId]
        Chats.select(Chats.id)
            .where { Chats.id eq conversationId }
            .forUpdate()
            .firstOrNull()
            ?: return@transaction null
        val message = MessagingV2Messages.selectAll()
            .where {
                (MessagingV2Messages.id eq messageId) and
                    (MessagingV2Messages.conversationId eq conversationId) and
                    (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
            }
            .forUpdate()
            .firstOrNull()
            ?: return@transaction null
        val attachmentIds = EncryptedAttachments.select(EncryptedAttachments.id)
            .where { EncryptedAttachments.messageId eq messageId }
            .forUpdate()
            .map { it[EncryptedAttachments.id] }
        if (attachmentIds.isNotEmpty()) {
            EncryptedAttachments.deleteWhere { EncryptedAttachments.id inList attachmentIds }
        }
        StarMessages.deleteWhere { StarMessages.messageId eq messageId }
        PinnedMessages.deleteWhere { PinnedMessages.messageId eq messageId }
        ServiceMessageReactions.deleteWhere { ServiceMessageReactions.messageId eq messageId }
        ServiceMessages.deleteWhere { ServiceMessages.id eq messageId }
        MessagingV2Envelopes.deleteWhere { MessagingV2Envelopes.messageId eq messageId }
        MessagingV2Messages.deleteWhere { MessagingV2Messages.id eq messageId }
        MessagingV2ModerationDeleteResult(
            metadata = message.toMetadata(),
            deletedAttachmentIds = attachmentIds,
        )
    }


    private fun org.jetbrains.exposed.sql.ResultRow.toMetadata() = MessagingV2MessageMetadata(
        id = this[MessagingV2Messages.id],
        conversationId = this[MessagingV2Messages.conversationId],
        senderUserId = this[MessagingV2Messages.senderUserId],
        recordClass = this[MessagingV2Messages.recordClass],
    )


    private fun digestService(message: MessageResponse, content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            message.id,
            message.chatId,
            message.senderId,
            message.type,
            message.timestamp.toString(),
            content,
        ).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun digestServiceEvent(
        id: String,
        conversationId: String,
        senderUserId: String,
        clientTimestamp: Long,
        content: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(id, conversationId, senderUserId, clientTimestamp.toString(), content).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun ServiceMessagingV2Event.isValidServiceMutation(): Boolean = when (action) {
        ACTION_EDIT -> content != null && editedAt != null && reactionEmoji == null
        ACTION_DELETE -> content == null && editedAt == null && reactionEmoji == null
        ACTION_REACTION_SET -> content == null && editedAt == null && !reactionEmoji.isNullOrBlank()
        else -> false
    }

    private companion object {
        const val KIND_SERVICE = "SERVICE"
        const val CIPHERTEXT_SERVICE = "SERVICE_PLAINTEXT"
        const val SERVICE_DEVICE_ID = 0
        const val TYPE_EVENT = "EVENT"
        const val ACTION_EDIT = "EDIT"
        const val ACTION_DELETE = "DELETE"
        const val ACTION_REACTION_SET = "REACTION_SET"
        const val SYSTEM_SENDER_ID = "system"
    }
}
