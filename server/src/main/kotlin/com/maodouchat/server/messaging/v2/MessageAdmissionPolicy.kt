package com.maodouchat.server.messaging.v2

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.db.MessagingV2Envelopes
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * B06：消息准入子域。校验发送者状态、revision、覆盖集与幂等，并在单事务内写入
 * metadata + envelopes。设备快照/拉黑过滤委托 [ConversationDeviceSnapshotStore]。
 */
class MessageAdmissionPolicy(
    private val snapshotStore: ConversationDeviceSnapshotStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun send(
        command: SendMessageV2Command,
        admitNewMessage: () -> Boolean = { true },
    ): SendMessageV2Result = transaction {
        val now = clock()
        val chat = Chats.selectAll()
            .where { Chats.id eq command.conversationId }
            .forUpdate()
            .firstOrNull()
            ?: throw MessagingV2ConversationNotFoundException()

        val participants = ChatParticipants.selectAll()
            .where { ChatParticipants.chatId eq command.conversationId }
            .toList()
        val senderParticipant = participants.firstOrNull { it[ChatParticipants.userId] == command.senderUserId }
            ?: throw MessagingV2NotParticipantException()
        val sender = Users.selectAll()
            .where { Users.id eq command.senderUserId }
            .forUpdate()
            .firstOrNull()
            ?: throw MessagingV2NotParticipantException()
        if (sender[Users.deletedAt] != null || sender[Users.suspendedUntil] > now) {
            throw MessagingV2SenderRestrictedException()
        }
        if (command.kind in USER_MUTATION_KINDS && sender[Users.messageRestrictedUntil] > now) {
            throw MessagingV2SenderRestrictedException()
        }
        if (command.kind in USER_MUTATION_KINDS && senderParticipant[ChatParticipants.mutedUntil] > now) {
            throw MessagingV2SenderMutedException()
        }

        val isGroup = chat[Chats.isGroup]
        if (!isGroup && command.kind in GROUP_CONTROL_KINDS) {
            throw MessagingV2ProtocolViolationException()
        }
        if (
            chat[Chats.chatType] == CHAT_TYPE_CHANNEL &&
            command.kind == KIND_DATA &&
            senderParticipant[ChatParticipants.role] != ROLE_OWNER
        ) {
            throw MessagingV2ChannelReadOnlyException()
        }
        val revision = chat[Chats.memberRevision]
        if (isGroup && command.groupRevision != revision) {
            throw MessagingV2RevisionMismatchException(revision)
        }

        val requestDigest = digest(command)
        val existing = MessagingV2Messages.selectAll()
            .where { MessagingV2Messages.id eq command.id }
            .forUpdate()
            .firstOrNull()
        if (existing != null) {
            if (
                existing[MessagingV2Messages.senderUserId] != command.senderUserId ||
                existing[MessagingV2Messages.conversationId] != command.conversationId ||
                existing[MessagingV2Messages.requestDigest] != requestDigest
            ) {
                throw MessagingV2DuplicateMessageException()
            }
            val envelopeRows = MessagingV2Envelopes.selectAll()
                .where { MessagingV2Envelopes.messageId eq command.id }
                .toList()
            return@transaction SendMessageV2Result(
                messageId = command.id,
                serverTimestamp = existing[MessagingV2Messages.serverTimestamp],
                envelopeCount = envelopeRows.size,
                idempotentReplay = true,
                recipientUserIds = envelopeRows.mapTo(linkedSetOf()) {
                    it[MessagingV2Envelopes.recipientUserId]
                },
            )
        }
        if (!admitNewMessage()) throw MessagingV2RateLimitedException()

        val participantIds = participants.map { it[ChatParticipants.userId] }
        val blockedPeerIds = snapshotStore.blockedPeerIds(command.senderUserId, participantIds)
        if (!isGroup && blockedPeerIds.isNotEmpty()) {
            throw MessagingV2BlockedConversationException()
        }
        val deliverableParticipantIds = participantIds.filterNot { it in blockedPeerIds }
        val expectedTargets = snapshotStore.confirmedEncryptableDeviceTargets(deliverableParticipantIds).apply {
            remove(DeviceTarget(command.senderUserId, command.senderDeviceId))
        }
        val requiredHumanRecipients = deliverableParticipantIds.filterNot {
            it == command.senderUserId || it.startsWith("bot_")
        }.toSet()
        val coveredHumanRecipients = expectedTargets.mapTo(linkedSetOf()) { it.userId }
        // Direct messages remain strict: silently accepting a message with no
        // peer device would make it appear sent while nobody can decrypt it.
        // Group messages are mailbox-backed and may proceed with the devices
        // that currently have complete bundles; missing members can receive a
        // later Sender Key redistribution after their device becomes ready.
        if (!isGroup && (requiredHumanRecipients - coveredHumanRecipients).isNotEmpty()) {
            throw MessagingV2CoverageException(emptySet(), emptySet())
        }
        val providedTargets = command.envelopes.mapTo(linkedSetOf()) { it.target }
        if (providedTargets.size != command.envelopes.size) {
            throw MessagingV2CoverageException(emptySet(), emptySet())
        }
        val missing = expectedTargets - providedTargets
        val unexpected = providedTargets - expectedTargets
        if (missing.isNotEmpty() || unexpected.isNotEmpty()) {
            throw MessagingV2CoverageException(missing, unexpected)
        }

        MessagingV2Messages.insert {
            it[id] = command.id
            it[conversationId] = command.conversationId
            it[senderUserId] = command.senderUserId
            it[senderDeviceId] = command.senderDeviceId
            it[kind] = command.kind
            it[recordClass] = if (command.kind == KIND_DATA) {
                MessagingV2RecordClass.MESSAGE
            } else {
                MessagingV2RecordClass.INTERNAL
            }
            it[groupRevision] = if (isGroup) revision else null
            it[clientTimestamp] = command.clientTimestamp
            it[serverTimestamp] = now
            it[MessagingV2Messages.requestDigest] = requestDigest
        }
        command.envelopes.forEach { envelope ->
            MessagingV2Envelopes.insert {
                it[id] = UUID.randomUUID().toString()
                it[messageId] = command.id
                it[recipientUserId] = envelope.target.userId
                it[recipientDeviceId] = envelope.target.deviceId
                it[ciphertextType] = envelope.ciphertextType
                it[ciphertext] = envelope.ciphertext
                it[serverTimestamp] = now
                it[acknowledgedAt] = null
            }
        }
        if (command.attachmentIds.isNotEmpty()) {
            val attachments = EncryptedAttachments.selectAll().where {
                (EncryptedAttachments.id inList command.attachmentIds) and
                    (EncryptedAttachments.chatId eq command.conversationId) and
                    (EncryptedAttachments.uploaderId eq command.senderUserId) and
                    (EncryptedAttachments.messageId eq command.id)
            }.forUpdate().toList()
            if (attachments.size != command.attachmentIds.toSet().size || attachments.any {
                    it[EncryptedAttachments.status] !in setOf("UPLOADED", "COMMITTED")
                }) {
                throw MessagingV2AttachmentNotReadyException()
            }
            EncryptedAttachments.update({
                (EncryptedAttachments.id inList command.attachmentIds) and
                    (EncryptedAttachments.chatId eq command.conversationId) and
                    (EncryptedAttachments.uploaderId eq command.senderUserId) and
                    (EncryptedAttachments.messageId eq command.id)
            }) {
                it[EncryptedAttachments.status] = "COMMITTED"
                it[EncryptedAttachments.expiresAt] = null
            }
        }
        SendMessageV2Result(
            messageId = command.id,
            serverTimestamp = now,
            envelopeCount = command.envelopes.size,
            idempotentReplay = false,
            recipientUserIds = command.envelopes.mapTo(linkedSetOf()) { it.target.userId },
        )
    }

    private fun digest(command: SendMessageV2Command): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        add(command.conversationId)
        add(command.senderUserId)
        add(command.senderDeviceId.toString())
        add(command.kind)
        add(command.clientTimestamp.toString())
        add(command.groupRevision?.toString().orEmpty())
        command.attachmentIds.sorted().forEach(::add)
        command.envelopes.sortedWith(compareBy({ it.target.userId }, { it.target.deviceId })).forEach {
            add(it.target.userId)
            add(it.target.deviceId.toString())
            add(it.ciphertextType)
            add(it.ciphertext)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        val USER_MUTATION_KINDS = setOf("DATA", "EVENT")
        val GROUP_CONTROL_KINDS = setOf("SENDER_KEY", "KEY_REQUEST")
        const val CHAT_TYPE_CHANNEL = "CHANNEL"
        const val ROLE_OWNER = "OWNER"
        const val KIND_DATA = "DATA"
    }
}
