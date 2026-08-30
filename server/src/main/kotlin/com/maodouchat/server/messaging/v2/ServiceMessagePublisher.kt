package com.maodouchat.server.messaging.v2

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Envelopes
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.SignalDevices
import com.maodouchat.server.model.MessageResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * B06：服务消息发布子域。bot / system 服务内容以明文发布，与设备邮箱行在同一事务
 * 内提交；幂等由 (id + requestDigest) 保证。
 */
class ServiceMessagePublisher(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { encodeDefaults = true }

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
