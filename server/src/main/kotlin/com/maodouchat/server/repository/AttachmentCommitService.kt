package com.maodouchat.server.repository

import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.messaging.v2.MessagingV2RecordClass
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/** B07：加密附件提交/绑定/回收子域（读取、绑定活跃消息、按消息/上传者/过期删除）。 */
class AttachmentCommitService {
    fun get(id: String): EncryptedAttachmentRecord? = transaction {
        EncryptedAttachments.selectAll().where { EncryptedAttachments.id eq id }.firstOrNull()?.toRecord()
    }

    fun allIds(): Set<String> = transaction {
        EncryptedAttachments.select(EncryptedAttachments.id).mapTo(linkedSetOf()) { it[EncryptedAttachments.id] }
    }

    fun isBoundToLiveMessage(id: String): Boolean = transaction {
        val attachment = EncryptedAttachments.selectAll().where {
            (EncryptedAttachments.id eq id) and (EncryptedAttachments.status eq STATUS_COMMITTED)
        }.limit(1).firstOrNull() ?: return@transaction false
        val messageId = attachment[EncryptedAttachments.messageId] ?: return@transaction false
        MessagingV2Messages.select(MessagingV2Messages.id).where {
            (MessagingV2Messages.id eq messageId) and
                (MessagingV2Messages.conversationId eq attachment[EncryptedAttachments.chatId]) and
                (MessagingV2Messages.senderUserId eq attachment[EncryptedAttachments.uploaderId]) and
                (MessagingV2Messages.recordClass eq MessagingV2RecordClass.MESSAGE)
        }.limit(1).any()
    }

    fun deleteExpired(now: Long = System.currentTimeMillis()): List<String> = transaction {
        val ids = EncryptedAttachments.selectAll()
            .where { (EncryptedAttachments.status neq STATUS_COMMITTED) and (EncryptedAttachments.expiresAt lessEq now) }
            .orderBy(EncryptedAttachments.createdAt to SortOrder.ASC)
            .forUpdate()
            .map { it[EncryptedAttachments.id] }
        if (ids.isNotEmpty()) {
            EncryptedAttachments.deleteWhere {
                (EncryptedAttachments.id inList ids) and
                    (EncryptedAttachments.status neq STATUS_COMMITTED) and
                    (EncryptedAttachments.expiresAt lessEq now)
            }
        }
        ids
    }

    fun deleteForMessage(messageId: String): List<String> = transaction {
        // FOR UPDATE keeps moderation/account deletion serialized with message send.
        val ids = EncryptedAttachments.selectAll()
            .where { EncryptedAttachments.messageId eq messageId }
            .forUpdate()
            .map { it[EncryptedAttachments.id] }
        if (ids.isNotEmpty()) EncryptedAttachments.deleteWhere { EncryptedAttachments.id inList ids }
        ids
    }

    fun deleteForUploader(userId: String): List<String> = transaction {
        val ids = EncryptedAttachments.selectAll().where { EncryptedAttachments.uploaderId eq userId }
            .forUpdate()
            .map { it[EncryptedAttachments.id] }
        if (ids.isNotEmpty()) EncryptedAttachments.deleteWhere { EncryptedAttachments.id inList ids }
        ids
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toRecord() = EncryptedAttachmentRecord(
        id = this[EncryptedAttachments.id],
        chatId = this[EncryptedAttachments.chatId],
        uploaderId = this[EncryptedAttachments.uploaderId],
        messageId = this[EncryptedAttachments.messageId],
        cipherSha256 = this[EncryptedAttachments.cipherSha256],
        cipherSize = this[EncryptedAttachments.cipherSize],
        uploadedBytes = this[EncryptedAttachments.uploadedBytes],
        status = this[EncryptedAttachments.status],
        createdAt = this[EncryptedAttachments.createdAt],
        expiresAt = this[EncryptedAttachments.expiresAt]
    )

    private companion object {
        const val STATUS_COMMITTED = "COMMITTED"
    }
}
