package com.maodouchat.server.service

import com.maodouchat.server.db.EncryptedAttachments
import com.maodouchat.server.repository.AttachmentStatus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

// 进度/终态域：分块进度上报、上传完成标记、未提交会话移除。

fun UploadSessionService.updateUploadProgress(id: String, userId: String, uploadedBytes: Long): Boolean = transaction {
    val row = EncryptedAttachments.selectAll().where {
        (EncryptedAttachments.id eq id) and (EncryptedAttachments.uploaderId eq userId)
    }.forUpdate().firstOrNull() ?: return@transaction false
    if (row[EncryptedAttachments.status] != AttachmentStatus.UPLOADING.dbValue) {
        return@transaction row[EncryptedAttachments.status] == AttachmentStatus.UPLOADED.dbValue || row[EncryptedAttachments.status] == AttachmentStatus.COMMITTED.dbValue
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

fun UploadSessionService.markUploaded(id: String, userId: String): Boolean = transaction {
    val row = EncryptedAttachments.selectAll().where {
        (EncryptedAttachments.id eq id) and (EncryptedAttachments.uploaderId eq userId)
    }.forUpdate().firstOrNull() ?: return@transaction false
    if (row[EncryptedAttachments.status] == AttachmentStatus.UPLOADED.dbValue || row[EncryptedAttachments.status] == AttachmentStatus.COMMITTED.dbValue) {
        return@transaction true
    }
    if (row[EncryptedAttachments.status] != AttachmentStatus.UPLOADING.dbValue) return@transaction false
    EncryptedAttachments.update({ EncryptedAttachments.id eq id }) {
        it[EncryptedAttachments.uploadedBytes] = row[EncryptedAttachments.cipherSize]
        it[EncryptedAttachments.status] = AttachmentStatus.UPLOADED.dbValue
    } == 1
}

fun UploadSessionService.removeUncommitted(id: String, userId: String): Boolean = transaction {
    EncryptedAttachments.deleteWhere {
        (EncryptedAttachments.id eq id) and
            (EncryptedAttachments.uploaderId eq userId) and
            (EncryptedAttachments.status neq AttachmentStatus.COMMITTED.dbValue)
    } == 1
}
