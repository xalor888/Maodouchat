package com.maodouchat.server.repository

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

/**
 * B07：加密附件门面。上传会话与提交/回收分属 [UploadSessionService] 与
 * [AttachmentCommitService]，本类仅组合依赖并保留原公开 API。
 */
class EncryptedAttachmentRepository {
    private val uploadSessionService = UploadSessionService()
    private val commitService = AttachmentCommitService()

    fun hasCapacityFor(uploaderId: String, chatId: String, pendingMessageId: String, cipherSize: Long, maxUserBytes: Long): Boolean =
        uploadSessionService.hasCapacityFor(uploaderId, chatId, pendingMessageId, cipherSize, maxUserBytes)

    fun createReplacingPending(id: String, chatId: String, uploaderId: String, pendingMessageId: String, sha256: String, cipherSize: Long, expiresAt: Long, maxUserBytes: Long): List<String> =
        uploadSessionService.createReplacingPending(id, chatId, uploaderId, pendingMessageId, sha256, cipherSize, expiresAt, maxUserBytes)

    fun createUploadSession(id: String, chatId: String, uploaderId: String, pendingMessageId: String, sha256: String, cipherSize: Long, expiresAt: Long, maxUserBytes: Long): AttachmentUploadSession =
        uploadSessionService.createUploadSession(id, chatId, uploaderId, pendingMessageId, sha256, cipherSize, expiresAt, maxUserBytes)

    fun updateUploadProgress(id: String, userId: String, uploadedBytes: Long): Boolean =
        uploadSessionService.updateUploadProgress(id, userId, uploadedBytes)

    fun markUploaded(id: String, userId: String): Boolean =
        uploadSessionService.markUploaded(id, userId)

    fun removeUncommitted(id: String, userId: String): Boolean =
        uploadSessionService.removeUncommitted(id, userId)

    fun get(id: String): EncryptedAttachmentRecord? = commitService.get(id)

    fun allIds(): Set<String> = commitService.allIds()

    fun isBoundToLiveMessage(id: String): Boolean = commitService.isBoundToLiveMessage(id)

    fun deleteExpired(now: Long = System.currentTimeMillis()): List<String> = commitService.deleteExpired(now)

    fun deleteForMessage(messageId: String): List<String> = commitService.deleteForMessage(messageId)

    fun deleteForUploader(userId: String): List<String> = commitService.deleteForUploader(userId)
}
