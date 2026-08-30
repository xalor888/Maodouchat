package com.maodouchat.server.messaging.v2

import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.db.MessagingV2Envelopes
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.PinnedMessages
import com.maodouchat.server.db.ServiceMessageReactions
import com.maodouchat.server.db.ServiceMessages
import com.maodouchat.server.db.StarMessages
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** B06：消息元数据子域。单条元数据读取、审核删除与 cascade 清理。 */
class MessageMetadataStore {
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
}
