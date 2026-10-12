package com.maodouchat.server.service

import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.repository.AttachmentStatus
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

// 配额域：容量预检 + 上传者名下未过期附件字节统计（SQL/内存同口径）。

fun UploadSessionService.hasCapacityFor(
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
            (EncryptedAttachments.status neq AttachmentStatus.COMMITTED.dbValue) and
            unexpiredAttachments(now)
    }.sumOf { it[EncryptedAttachments.cipherSize] }
    activeBytesForUploaderInTransaction(uploaderId) - replaceableBytes + cipherSize <= maxUserBytes
}

internal fun UploadSessionService.activeBytesForUploaderInTransaction(userId: String): Long {
    val now = System.currentTimeMillis()
    return EncryptedAttachments
        .select(EncryptedAttachments.cipherSize)
        .where {
            (EncryptedAttachments.uploaderId eq userId) and
                // 排除已过期但尚未被定时任务清理的未提交附件，防止配额虚高
                unexpiredAttachments(now)
        }
        .sumOf { it[EncryptedAttachments.cipherSize] }
}

// 内存侧「未过期」：expiresAt 为 null（永不过期）或晚于 now——与 SQL 侧谓词同口径。
internal fun UploadSessionService.isUnexpired(row: ResultRow, now: Long): Boolean =
    row[EncryptedAttachments.expiresAt] == null || row[EncryptedAttachments.expiresAt]!! > now

// SQL 侧同口径谓词：配额计算的 where 里共用，别跟内存侧判定漂移。
internal fun SqlExpressionBuilder.unexpiredAttachments(now: Long): Op<Boolean> =
    (EncryptedAttachments.expiresAt.isNull()) or (EncryptedAttachments.expiresAt greater now)
