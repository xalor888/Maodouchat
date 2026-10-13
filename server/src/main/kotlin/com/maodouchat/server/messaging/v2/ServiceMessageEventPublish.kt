package com.maodouchat.server.messaging.v2

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.MessagingV2Envelopes
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.SignalDevices
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

fun ServiceMessagePublisher.enqueueServiceEvent(
    id: String,
    conversationId: String,
    senderUserId: String,
    clientTimestamp: Long,
    event: ServiceMessagingV2Event,
    recipientUserIds: Set<String>,
): SendMessageV2Result = transaction {
    require(senderUserId.startsWith("bot_") || senderUserId == ServiceMessagePublisher.SYSTEM_SENDER_ID) {
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
    if (senderUserId != ServiceMessagePublisher.SYSTEM_SENDER_ID && senderUserId !in participantIds) {
        throw MessagingV2NotParticipantException()
    }
    if (senderUserId == ServiceMessagePublisher.SYSTEM_SENDER_ID && event.action != ServiceMessagePublisher.ACTION_DELETE) {
        throw IllegalArgumentException("system_service_event_not_allowed")
    }

    val serviceContent = json.encodeToString(
        ServiceMessagingV2Content(
            type = ServiceMessagePublisher.TYPE_EVENT,
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
            existing[MessagingV2Messages.kind] != ServiceMessagePublisher.KIND_SERVICE ||
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
        it[senderDeviceId] = ServiceMessagePublisher.SERVICE_DEVICE_ID
        it[kind] = ServiceMessagePublisher.KIND_SERVICE
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
            it[ciphertextType] = ServiceMessagePublisher.CIPHERTEXT_SERVICE
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
