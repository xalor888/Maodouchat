package com.maodouchat.server.repository

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.db.Messages
import com.maodouchat.server.db.Users
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class EncryptedAttachmentRecord(
    val id: String,
    val chatId: String,
    val uploaderId: String,
    val messageId: String?,
    val cipherSha256: String,
    val cipherSize: Long,
    val uploadedBytes: Long,
    val status: String,
    val createdAt: Long,
    val expiresAt: Long?
)

class AttachmentQuotaExceededException : IllegalStateException("attachment_quota_exceeded")
class AttachmentMessageAlreadyUsedException : IllegalStateException("attachment_message_already_used")
/** 创建上传会话时成员/禁言校验失败（路由预检与 insert 之间的 TOCTOU） */
class AttachmentNotAllowedException(reason: String = "attachment_not_allowed") : IllegalStateException(reason)

data class AttachmentUploadSession(
    val record: EncryptedAttachmentRecord,
    val replacedIds: List<String>,
    val reused: Boolean
)

class EncryptedAttachmentRepository {
    fun hasCapacityFor(
        uploaderId: String,
        chatId: String,
        pendingMessageId: String,
        cipherSize: Long,
        maxUserBytes: Long
    ): Boolean = transaction {
        val now = System.currentTimeMillis()
        val replaceableBytes = EncryptedAttachments.selectAll().where {
            (EncryptedAttachments.uploaderId eq uploaderId) and
                (EncryptedAttachments.chatId eq chatId) and
                (EncryptedAttachments.messageId eq pendingMessageId) and
                (EncryptedAttachments.status neq STATUS_COMMITTED) and
                ((EncryptedAttachments.expiresAt.isNull()) or (EncryptedAttachments.expiresAt greater now))
        }.sumOf { it[EncryptedAttachments.cipherSize] }
        activeBytesForUploaderInTransaction(uploaderId) - replaceableBytes + cipherSize <= maxUserBytes
    }

    fun createReplacingPending(
        id: String,
        chatId: String,
        uploaderId: String,
        pendingMessageId: String,
        sha256: String,
        cipherSize: Long,
        expiresAt: Long,
        maxUserBytes: Long
    ): List<String> = transaction {
        val user = Users.selectAll()
            .where { Users.id eq uploaderId }
            .forUpdate()
            .limit(1)
            .firstOrNull()
        if (user == null || user[Users.deletedAt] != null) throw AttachmentMessageAlreadyUsedException()
        // 与 createUploadSession / sendMessage 一致：锁 chat + 成员行，复检参与/禁言
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: throw AttachmentNotAllowedException("chat_not_found")
        val participantRow = ChatParticipants.selectAll().where {
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq uploaderId)
        }.forUpdate().firstOrNull()
            ?: throw AttachmentNotAllowedException("not_participant")
        if (chat[Chats.isGroup] &&
            participantRow[ChatParticipants.role] != "OWNER" &&
            participantRow[ChatParticipants.mutedUntil] > System.currentTimeMillis()
        ) {
            throw AttachmentNotAllowedException("muted")
        }
        if (Messages.selectAll().where { Messages.id eq pendingMessageId }.limit(1).firstOrNull() != null) {
            throw AttachmentMessageAlreadyUsedException()
        }
        val existing = EncryptedAttachments.selectAll().where {
            (EncryptedAttachments.uploaderId eq uploaderId) and
                (EncryptedAttachments.chatId eq chatId) and
                (EncryptedAttachments.messageId eq pendingMessageId)
        }.forUpdate().toList()
        if (existing.any { it[EncryptedAttachments.status] == STATUS_COMMITTED }) {
            throw AttachmentMessageAlreadyUsedException()
        }
        val replacedIds = existing.map { it[EncryptedAttachments.id] }
        val nowForExpiry = System.currentTimeMillis()
        val replacedBytes = existing.filter { row ->
            row[EncryptedAttachments.expiresAt] == null || row[EncryptedAttachments.expiresAt]!! > nowForExpiry
        }.sumOf { it[EncryptedAttachments.cipherSize] }
        val projectedBytes = activeBytesForUploaderInTransaction(uploaderId) - replacedBytes + cipherSize
        if (projectedBytes > maxUserBytes) throw AttachmentQuotaExceededException()
        if (replacedIds.isNotEmpty()) {
            EncryptedAttachments.deleteWhere { EncryptedAttachments.id inList replacedIds }
        }
        EncryptedAttachments.insert {
            it[EncryptedAttachments.id] = id
            it[EncryptedAttachments.chatId] = chatId
            it[EncryptedAttachments.uploaderId] = uploaderId
            it[EncryptedAttachments.messageId] = pendingMessageId
            it[EncryptedAttachments.cipherSha256] = sha256
            it[EncryptedAttachments.cipherSize] = cipherSize
            it[EncryptedAttachments.uploadedBytes] = cipherSize
            it[EncryptedAttachments.status] = STATUS_UPLOADED
            it[EncryptedAttachments.createdAt] = System.currentTimeMillis()
            it[EncryptedAttachments.expiresAt] = expiresAt
        }
        replacedIds
    }

    fun createUploadSession(
        id: String,
        chatId: String,
        uploaderId: String,
        pendingMessageId: String,
        sha256: String,
        cipherSize: Long,
        expiresAt: Long,
        maxUserBytes: Long
    ): AttachmentUploadSession = transaction {
        val user = Users.selectAll()
            .where { Users.id eq uploaderId }
            .forUpdate()
            .limit(1)
            .firstOrNull()
        if (user == null || user[Users.deletedAt] != null) throw AttachmentMessageAlreadyUsedException()
        // 与 sendMessage 一致：锁 chat + 成员行，复检参与/禁言，避免路由预检后被踢/禁言仍建会话
        val chat = Chats.selectAll().where { Chats.id eq chatId }.forUpdate().firstOrNull()
            ?: throw AttachmentNotAllowedException("chat_not_found")
        val participantRow = ChatParticipants.selectAll().where {
            (ChatParticipants.chatId eq chatId) and (ChatParticipants.userId eq uploaderId)
        }.forUpdate().firstOrNull()
            ?: throw AttachmentNotAllowedException("not_participant")
        if (chat[Chats.isGroup] &&
            participantRow[ChatParticipants.role] != "OWNER" &&
            participantRow[ChatParticipants.mutedUntil] > System.currentTimeMillis()
        ) {
            throw AttachmentNotAllowedException("muted")
        }
        if (Messages.selectAll().where { Messages.id eq pendingMessageId }.limit(1).firstOrNull() != null) {
            throw AttachmentMessageAlreadyUsedException()
        }
        val existing = EncryptedAttachments.selectAll().where {
            (EncryptedAttachments.uploaderId eq uploaderId) and
                (EncryptedAttachments.chatId eq chatId) and
                (EncryptedAttachments.messageId eq pendingMessageId)
        }.forUpdate().toList()
        if (existing.any { it[EncryptedAttachments.status] == STATUS_COMMITTED }) {
            throw AttachmentMessageAlreadyUsedException()
        }
        val nowForExpiry = System.currentTimeMillis()
        // BUG-4 fix: 仅复用未过期的附件，防止跳过配额检查
        val reusable = existing.singleOrNull { row ->
            row[EncryptedAttachments.cipherSha256] == sha256 &&
                row[EncryptedAttachments.cipherSize] == cipherSize &&
                (row[EncryptedAttachments.expiresAt] == null || row[EncryptedAttachments.expiresAt]!! > nowForExpiry)
        }
        if (reusable != null) {
            // activeBytes 已包含当前可复用行，不能再重复累加一次。
            val projectedReuseBytes = activeBytesForUploaderInTransaction(uploaderId)
            if (projectedReuseBytes > maxUserBytes) throw AttachmentQuotaExceededException()
            EncryptedAttachments.update({ EncryptedAttachments.id eq reusable[EncryptedAttachments.id] }) {
                it[EncryptedAttachments.expiresAt] = expiresAt
            }
            return@transaction AttachmentUploadSession(
                record = reusable.toRecord().copy(expiresAt = expiresAt),
                replacedIds = emptyList(),
                reused = true
            )
        }
        val replacedIds = existing.map { it[EncryptedAttachments.id] }
        // BUG-5 fix: replacedBytes 仅计算未过期的附件，与 activeBytes 的过期过滤一致
        val replacedBytes = existing.filter { row ->
            row[EncryptedAttachments.expiresAt] == null || row[EncryptedAttachments.expiresAt]!! > nowForExpiry
        }.sumOf { it[EncryptedAttachments.cipherSize] }
        val projectedBytes = activeBytesForUploaderInTransaction(uploaderId) - replacedBytes + cipherSize
        if (projectedBytes > maxUserBytes) throw AttachmentQuotaExceededException()
        if (replacedIds.isNotEmpty()) {
            EncryptedAttachments.deleteWhere { EncryptedAttachments.id inList replacedIds }
        }
        val now = System.currentTimeMillis()
        EncryptedAttachments.insert {
            it[EncryptedAttachments.id] = id
            it[EncryptedAttachments.chatId] = chatId
            it[EncryptedAttachments.uploaderId] = uploaderId
            it[EncryptedAttachments.messageId] = pendingMessageId
            it[EncryptedAttachments.cipherSha256] = sha256
            it[EncryptedAttachments.cipherSize] = cipherSize
            it[EncryptedAttachments.uploadedBytes] = 0L
            it[EncryptedAttachments.status] = STATUS_UPLOADING
            it[EncryptedAttachments.createdAt] = now
            it[EncryptedAttachments.expiresAt] = expiresAt
        }
        AttachmentUploadSession(
            record = EncryptedAttachmentRecord(
                id = id,
                chatId = chatId,
                uploaderId = uploaderId,
                messageId = pendingMessageId,
                cipherSha256 = sha256,
                cipherSize = cipherSize,
                uploadedBytes = 0L,
                status = STATUS_UPLOADING,
                createdAt = now,
                expiresAt = expiresAt
            ),
            replacedIds = replacedIds,
            reused = false
        )
    }

    fun get(id: String): EncryptedAttachmentRecord? = transaction {
        EncryptedAttachments.selectAll().where { EncryptedAttachments.id eq id }.firstOrNull()?.toRecord()
    }

    fun allIds(): Set<String> = transaction {
        EncryptedAttachments.select(EncryptedAttachments.id).mapTo(linkedSetOf()) { it[EncryptedAttachments.id] }
    }

    private fun activeBytesForUploaderInTransaction(userId: String): Long {
        val now = System.currentTimeMillis()
        return EncryptedAttachments
            .select(EncryptedAttachments.cipherSize)
            .where {
                (EncryptedAttachments.uploaderId eq userId) and
                    // 排除已过期但尚未被定时任务清理的未提交附件，防止配额虚高
                    ((EncryptedAttachments.expiresAt.isNull()) or (EncryptedAttachments.expiresAt greater now))
            }
            .sumOf { it[EncryptedAttachments.cipherSize] }
    }

    fun updateUploadProgress(id: String, userId: String, uploadedBytes: Long): Boolean = transaction {
        val row = EncryptedAttachments.selectAll().where {
            (EncryptedAttachments.id eq id) and (EncryptedAttachments.uploaderId eq userId)
        }.forUpdate().firstOrNull() ?: return@transaction false
        if (row[EncryptedAttachments.status] != STATUS_UPLOADING) {
            return@transaction row[EncryptedAttachments.status] == STATUS_UPLOADED || row[EncryptedAttachments.status] == STATUS_COMMITTED
        }
        // 8.34 修复：并发分块提交乱序——A 写文件到 100、B 写到 200 且 B 的 DB 事务先提交，
        // A 的 100 相对当前 200 是回退。此前判定 false 会让路由销毁整个上传会话（行+文件），
        // 在途分块全部失败。文件写入已按 offset 串行校验（appendChunk Accepted），DB 落后无害，
        // 直接采纳现有更大进度。
        if (uploadedBytes < row[EncryptedAttachments.uploadedBytes]) return@transaction true
        if (uploadedBytes > row[EncryptedAttachments.cipherSize]) return@transaction false
        EncryptedAttachments.update({ EncryptedAttachments.id eq id }) {
            it[EncryptedAttachments.uploadedBytes] = uploadedBytes
        } == 1
    }

    fun markUploaded(id: String, userId: String): Boolean = transaction {
        val row = EncryptedAttachments.selectAll().where {
            (EncryptedAttachments.id eq id) and (EncryptedAttachments.uploaderId eq userId)
        }.forUpdate().firstOrNull() ?: return@transaction false
        if (row[EncryptedAttachments.status] == STATUS_UPLOADED || row[EncryptedAttachments.status] == STATUS_COMMITTED) {
            return@transaction true
        }
        if (row[EncryptedAttachments.status] != STATUS_UPLOADING) return@transaction false
        EncryptedAttachments.update({ EncryptedAttachments.id eq id }) {
            it[EncryptedAttachments.uploadedBytes] = row[EncryptedAttachments.cipherSize]
            it[EncryptedAttachments.status] = STATUS_UPLOADED
        } == 1
    }

    fun commit(id: String, userId: String, messageId: String): Boolean = transaction {
        // MessageRepository 的删除/撤回统一按 message -> attachment 加锁；这里保持同一顺序，避免互等。
        val message = Messages.selectAll().where { Messages.id eq messageId }.forUpdate().firstOrNull()
            ?: return@transaction false
        if (
            message[Messages.senderId] != userId ||
            message[Messages.type] !in ATTACHMENT_MESSAGE_TYPES
        ) return@transaction false
        val attachment = EncryptedAttachments.selectAll().where { EncryptedAttachments.id eq id }.forUpdate().firstOrNull()
            ?: return@transaction false
        if (
            attachment[EncryptedAttachments.uploaderId] != userId ||
            attachment[EncryptedAttachments.messageId] != messageId ||
            message[Messages.chatId] != attachment[EncryptedAttachments.chatId]
        ) return@transaction false
        if (attachment[EncryptedAttachments.status] == STATUS_COMMITTED) return@transaction true
        if (attachment[EncryptedAttachments.status] != STATUS_UPLOADED) return@transaction false
        EncryptedAttachments.update({ EncryptedAttachments.id eq id }) {
            it[EncryptedAttachments.status] = STATUS_COMMITTED
            it[EncryptedAttachments.expiresAt] = null
        } == 1
    }

    fun isBoundToLiveMessage(id: String): Boolean = transaction {
        val attachment = EncryptedAttachments.selectAll().where {
            (EncryptedAttachments.id eq id) and (EncryptedAttachments.status eq STATUS_COMMITTED)
        }.limit(1).firstOrNull() ?: return@transaction false
        val messageId = attachment[EncryptedAttachments.messageId] ?: return@transaction false
        Messages.select(Messages.id).where {
            (Messages.id eq messageId) and
                (Messages.chatId eq attachment[EncryptedAttachments.chatId]) and
                (Messages.senderId eq attachment[EncryptedAttachments.uploaderId]) and
                (Messages.type inList ATTACHMENT_MESSAGE_TYPES)
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
        // FOR UPDATE：与 commit 串行，避免删行与 COMMITTED 写交叉留下永不过期孤儿
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

    fun removeUncommitted(id: String, userId: String): Boolean = transaction {
        EncryptedAttachments.deleteWhere {
            (EncryptedAttachments.id eq id) and
                (EncryptedAttachments.uploaderId eq userId) and
                (EncryptedAttachments.status neq STATUS_COMMITTED)
        } == 1
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
        const val STATUS_UPLOADED = "UPLOADED"
        const val STATUS_UPLOADING = "UPLOADING"
        const val STATUS_COMMITTED = "COMMITTED"
    }
}
