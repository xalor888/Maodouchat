package com.maodouchat.attachment

import android.content.Context
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.dao.AttachmentTransferDao
import com.maodouchat.data.local.entity.AttachmentTransferEntity
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.domain.messaging.AttachmentTransfer
import com.maodouchat.domain.messaging.TransferRepository
import com.maodouchat.domain.messaging.TransferStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * M07: 基于 Room [AttachmentTransferDao] 的传输仓储实现，供域服务和 Controller 消费。
 */
class RoomTransferRepository(
    private val context: Context,
    private val dao: AttachmentTransferDao,
) : TransferRepository {

    constructor(app: MaodouchatApp) : this(app, app.database.attachmentTransferDao())

    override fun observe(transferId: String, ownerUserId: String): Flow<AttachmentTransfer?> {
        return dao.observeAll(ownerUserId).map { list ->
            list.firstOrNull { it.messageId == transferId }?.toDomain()
        }
    }

    override fun observeAll(chatId: String, ownerUserId: String): Flow<List<AttachmentTransfer>> {
        return dao.observeByChat(chatId, ownerUserId).map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun get(transferId: String, ownerUserId: String): AttachmentTransfer? {
        return dao.get(transferId, ownerUserId)?.toDomain()
    }

    override suspend fun updateStatus(transferId: String, ownerUserId: String, status: TransferStatus): Boolean {
        val entityState = when (status) {
            TransferStatus.PREPARING -> AttachmentTransferState.PREPARING
            TransferStatus.UPLOADING -> AttachmentTransferState.UPLOADING
            TransferStatus.PAUSED -> AttachmentTransferState.PAUSED
            TransferStatus.FINALIZING -> AttachmentTransferState.READY
            TransferStatus.COMMITTED -> return true
            TransferStatus.FAILED -> AttachmentTransferState.FAILED
            TransferStatus.CANCELLED -> return cancel(transferId, ownerUserId)
        }
        val existing = dao.get(transferId, ownerUserId) ?: return false
        dao.upsert(existing.copy(state = entityState, updatedAt = System.currentTimeMillis()))
        return true
    }

    override suspend fun updateProgress(
        transferId: String,
        ownerUserId: String,
        progressBytes: Long,
        totalBytes: Long?
    ): Boolean {
        val existing = dao.get(transferId, ownerUserId) ?: return false
        val newCipherSize = totalBytes ?: existing.cipherSize
        dao.upsert(
            existing.copy(
                uploadedBytes = progressBytes,
                cipherSize = newCipherSize,
                updatedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    override suspend fun pause(transferId: String, ownerUserId: String): Boolean {
        return AttachmentTransferCoordinator.pause(context, transferId)
    }

    override suspend fun resume(transferId: String, ownerUserId: String): Boolean {
        return AttachmentTransferCoordinator.resume(context, transferId, ownerUserId)
    }

    override suspend fun cancel(transferId: String, ownerUserId: String): Boolean {
        return AttachmentTransferCoordinator.cancel(context, transferId, ownerUserId)
    }

    companion object {
        fun AttachmentTransferEntity.toDomain(): AttachmentTransfer {
            val status = when (state) {
                AttachmentTransferState.PREPARING -> TransferStatus.PREPARING
                AttachmentTransferState.QUEUED,
                AttachmentTransferState.UPLOADING -> TransferStatus.UPLOADING
                AttachmentTransferState.READY,
                AttachmentTransferState.SENDING -> TransferStatus.FINALIZING
                AttachmentTransferState.PAUSED -> TransferStatus.PAUSED
                AttachmentTransferState.FAILED -> TransferStatus.FAILED
                else -> TransferStatus.UPLOADING
            }
            return AttachmentTransfer(
                transferId = messageId,
                status = status,
                progressBytes = uploadedBytes,
                totalBytes = cipherSize.takeIf { it > 0L },
                errorCode = lastErrorCode,
            )
        }
    }
}
