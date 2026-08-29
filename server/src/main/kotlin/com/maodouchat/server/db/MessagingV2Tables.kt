package com.maodouchat.server.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll

/** Immutable transport metadata. Plaintext and decrypted group state stay on client devices. */
object MessagingV2Messages : Table("messaging_v2_messages") {
    val id = varchar("id", 100)
    val conversationId = varchar("conversation_id", 50) references Chats.id
    /** User id for user/bot traffic, or a reserved service principal such as `system`. */
    val senderUserId = varchar("sender_user_id", 50)
    val senderDeviceId = integer("sender_device_id")
    val kind = varchar("kind", 32)
    /** MESSAGE is user-visible; EVENT/INTERNAL records cannot be starred or pinned. */
    val recordClass = varchar("record_class", 16).default("MESSAGE")
    val groupRevision = long("group_revision").nullable()
    val clientTimestamp = long("client_timestamp")
    val serverTimestamp = long("server_timestamp")
    val requestDigest = varchar("request_digest", 64)
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_messaging_v2_messages_conversation_time", false, conversationId, serverTimestamp)
        index("idx_messaging_v2_messages_sender_time", false, senderUserId, serverTimestamp)
    }
}

/** Durable mailbox row for one destination device. WebSocket only announces new rows. */
object MessagingV2Envelopes : Table("messaging_v2_envelopes") {
    val id = varchar("id", 100)
    val sequence = long("sequence").autoIncrement().uniqueIndex()
    val messageId = varchar("message_id", 100) references MessagingV2Messages.id
    val recipientUserId = varchar("recipient_user_id", 50) references Users.id
    val recipientDeviceId = integer("recipient_device_id")
    val ciphertextType = varchar("ciphertext_type", 32)
    val ciphertext = text("ciphertext")
    val serverTimestamp = long("server_timestamp")
    val acknowledgedAt = long("acknowledged_at").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(
            "uidx_messaging_v2_envelope_target",
            messageId,
            recipientUserId,
            recipientDeviceId,
        )
        index(
            "idx_messaging_v2_inbox_pending",
            false,
            recipientUserId,
            recipientDeviceId,
            acknowledgedAt,
            sequence,
        )
        index("idx_messaging_v2_envelope_message", false, messageId)
    }
}

/** Delete a conversation's durable transport rows before deleting the owning chat. */
fun deleteMessagingV2ConversationInTx(conversationId: String) {
    // Service rows can exist without transport metadata (for example, after a
    // partially migrated Bot conversation), so they must not depend on messageIds.
    ServiceMessages.deleteWhere { ServiceMessages.chatId eq conversationId }
    val messageIds = MessagingV2Messages.select(MessagingV2Messages.id)
        .where { MessagingV2Messages.conversationId eq conversationId }
        .forUpdate()
        .map { it[MessagingV2Messages.id] }
    if (messageIds.isEmpty()) return
    StarMessages.deleteWhere { StarMessages.messageId inList messageIds }
    PinnedMessages.deleteWhere { PinnedMessages.messageId inList messageIds }
    ServiceMessageReactions.deleteWhere { ServiceMessageReactions.messageId inList messageIds }
    MessagingV2Envelopes.deleteWhere { MessagingV2Envelopes.messageId inList messageIds }
    MessagingV2Messages.deleteWhere { MessagingV2Messages.id inList messageIds }
}

/** Remove server-side mailbox and per-user controls when a participant leaves. */
fun deleteMessagingV2ParticipantStateInTx(conversationId: String, userId: String) {
    val messageIds = MessagingV2Messages.select(MessagingV2Messages.id)
        .where { MessagingV2Messages.conversationId eq conversationId }
        .map { it[MessagingV2Messages.id] }
    if (messageIds.isEmpty()) return
    StarMessages.deleteWhere {
        (StarMessages.messageId inList messageIds) and (StarMessages.userId eq userId)
    }
    ServiceMessageReactions.deleteWhere {
        (ServiceMessageReactions.messageId inList messageIds) and
            (ServiceMessageReactions.botUserId eq userId)
    }
    MessagingV2Envelopes.deleteWhere {
        (MessagingV2Envelopes.messageId inList messageIds) and
            (MessagingV2Envelopes.recipientUserId eq userId)
    }
}
