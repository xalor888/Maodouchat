package com.maodouchat.server.plugins

import com.maodouchat.server.model.*
import com.maodouchat.server.repository.*
import com.maodouchat.server.service.BlobStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 附件分片上传会话共享支撑：状态转换与上传对账，供各会话簇模块复用。 */
internal fun EncryptedAttachmentRecord.toUploadStatus(uploadedBytesOverride: Long? = null): AttachmentUploadStatusResponse {
    val actualBytes = uploadedBytesOverride ?: if (status == AttachmentStatus.UPLOADING.dbValue) uploadedBytes else cipherSize
    return AttachmentUploadStatusResponse(
        id = id,
        cipherSha256 = cipherSha256,
        cipherSize = cipherSize,
        uploadedBytes = actualBytes.coerceIn(0L, cipherSize),
        status = status,
        expiresAt = expiresAt ?: 0L,
        complete = status == AttachmentStatus.UPLOADED.dbValue || status == AttachmentStatus.COMMITTED.dbValue
    )
}

internal suspend fun reconcileAttachmentUpload(
    record: EncryptedAttachmentRecord,
    repository: EncryptedAttachmentRepository,
    userId: String
): EncryptedAttachmentRecord? {
    if (record.status != AttachmentStatus.UPLOADING.dbValue) return record
    val actualBytes = withContext(Dispatchers.IO) { BlobStore.uploadedBytes(record.id) }
        ?.coerceAtMost(record.cipherSize) ?: 0L
    if (actualBytes < record.uploadedBytes) return null
    if (!repository.updateUploadProgress(record.id, userId, actualBytes)) return null
    if (actualBytes < record.cipherSize) return repository.get(record.id)?.copy(uploadedBytes = actualBytes)
    if (withContext(Dispatchers.IO) { BlobStore.sha256(record.id) } != record.cipherSha256) return null
    if (withContext(Dispatchers.IO) { BlobStore.finalizeResumableUpload(record.id) } == null) return null
    if (!repository.markUploaded(record.id, userId)) return null
    return repository.get(record.id)
}
