package com.maodouchat.server.service

import com.maodouchat.server.db.ChatParticipants
import com.maodouchat.server.db.Chats
import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.db.MessagingV2Messages
import com.maodouchat.server.db.Users
import com.maodouchat.server.repository.AttachmentMessageAlreadyUsedException
import com.maodouchat.server.repository.AttachmentNotAllowedException
import com.maodouchat.server.repository.AttachmentQuotaExceededException
import com.maodouchat.server.repository.AttachmentStatus
import com.maodouchat.server.repository.AttachmentUploadSession
import com.maodouchat.server.repository.EncryptedAttachmentRecord
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

// 会话创建域：新建/替换待定会话（含用户、会话、成员、禁言、消息复用、配额复检）。

fun UploadSessionService.createReplacingPending(
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
    if (MessagingV2Messages.selectAll().where { MessagingV2Messages.id eq pendingMessageId }.limit(1).firstOrNull() != null) {
        throw AttachmentMessageAlreadyUsedException()
    }
    val existing = EncryptedAttachments.selectAll().where {
        (EncryptedAttachments.uploaderId eq uploaderId) and
            (EncryptedAttachments.chatId eq chatId) and
            (EncryptedAttachments.messageId eq pendingMessageId)
    }.forUpdate().toList()
    if (existing.any { it[EncryptedAttachments.status] == AttachmentStatus.COMMITTED.dbValue }) {
        throw AttachmentMessageAlreadyUsedException()
    }
    val replacedIds = existing.map { it[EncryptedAttachments.id] }
    val nowForExpiry = System.currentTimeMillis()
    val replacedBytes = existing.filter { row ->
        isUnexpired(row, nowForExpiry)
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
        it[EncryptedAttachments.status] = AttachmentStatus.UPLOADED.dbValue
        it[EncryptedAttachments.createdAt] = System.currentTimeMillis()
        it[EncryptedAttachments.expiresAt] = expiresAt
    }
    replacedIds
}

fun UploadSessionService.createUploadSession(
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
    if (MessagingV2Messages.selectAll().where { MessagingV2Messages.id eq pendingMessageId }.limit(1).firstOrNull() != null) {
        throw AttachmentMessageAlreadyUsedException()
    }
    val existing = EncryptedAttachments.selectAll().where {
        (EncryptedAttachments.uploaderId eq uploaderId) and
            (EncryptedAttachments.chatId eq chatId) and
            (EncryptedAttachments.messageId eq pendingMessageId)
    }.forUpdate().toList()
    if (existing.any { it[EncryptedAttachments.status] == AttachmentStatus.COMMITTED.dbValue }) {
        throw AttachmentMessageAlreadyUsedException()
    }
    val nowForExpiry = System.currentTimeMillis()
    // BUG-4 fix: 仅复用未过期的附件，防止跳过配额检查
    val reusable = existing.singleOrNull { row ->
        row[EncryptedAttachments.cipherSha256] == sha256 &&
            row[EncryptedAttachments.cipherSize] == cipherSize &&
            isUnexpired(row, nowForExpiry)
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
        isUnexpired(row, nowForExpiry)
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
        it[EncryptedAttachments.status] = AttachmentStatus.UPLOADING.dbValue
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
            status = AttachmentStatus.UPLOADING.dbValue,
            createdAt = now,
            expiresAt = expiresAt
        ),
        replacedIds = replacedIds,
        reused = false
    )
}

private fun ResultRow.toRecord() = EncryptedAttachmentRecord(
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
