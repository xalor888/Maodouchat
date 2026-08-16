package com.maodouchat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.maodouchat.data.local.entity.AttachmentTransferEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentTransferDao {
    @Query("SELECT * FROM attachment_transfers ORDER BY createdAt ASC")
    fun observeAllAccounts(): Flow<List<AttachmentTransferEntity>>

    @Query("SELECT * FROM attachment_transfers WHERE messageId = :messageId AND ownerUserId = :ownerUserId")
    suspend fun get(messageId: String, ownerUserId: String): AttachmentTransferEntity?

    @Query("SELECT * FROM attachment_transfers WHERE chatId = :chatId AND ownerUserId = :ownerUserId ORDER BY createdAt ASC")
    fun observeByChat(chatId: String, ownerUserId: String): Flow<List<AttachmentTransferEntity>>

    // 8.49：账号级 Flow 直查——供传输汇总浮窗替代 750ms 全表轮询
    @Query("SELECT * FROM attachment_transfers WHERE ownerUserId = :ownerUserId ORDER BY createdAt ASC")
    fun observeAll(ownerUserId: String): Flow<List<AttachmentTransferEntity>>

    @Query("SELECT * FROM attachment_transfers WHERE chatId = :chatId AND ownerUserId = :ownerUserId")
    suspend fun getByChat(chatId: String, ownerUserId: String): List<AttachmentTransferEntity>

    @Query("SELECT * FROM attachment_transfers WHERE ownerUserId = :ownerUserId")
    suspend fun getAll(ownerUserId: String): List<AttachmentTransferEntity>

    @Query("SELECT * FROM attachment_transfers")
    suspend fun getAllAccounts(): List<AttachmentTransferEntity>

    @Query("SELECT * FROM attachment_transfers WHERE state = :state AND ownerUserId = :ownerUserId ORDER BY updatedAt ASC")
    suspend fun getByState(state: String, ownerUserId: String): List<AttachmentTransferEntity>

    @Query("SELECT ownerUserId FROM attachment_transfers WHERE messageId = :messageId")
    suspend fun getOwnerUserId(messageId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOwnedRow(transfer: AttachmentTransferEntity)

    @Transaction
    suspend fun upsert(transfer: AttachmentTransferEntity) {
        val existingOwnerUserId = getOwnerUserId(transfer.messageId)
        require(existingOwnerUserId == null || existingOwnerUserId == transfer.ownerUserId) {
            "attachment_transfer_message_owner_conflict"
        }
        upsertOwnedRow(transfer)
    }

    // 领取上传：QUEUED/FAILED 可随时领取；UPLOADING 仅当其已陈旧（updatedAt 早于 staleBeforeMs，
    // 默认 2 分钟）才可被二次领取，防止进程死亡+WorkManager 重试与在途实例同时领取 → 并发双上传/双 finalize → 重复消息。
    @Query("UPDATE attachment_transfers SET state = 'UPLOADING', updatedAt = :updatedAt, lastErrorCode = NULL WHERE messageId = :messageId AND ownerUserId = :ownerUserId AND (state IN ('QUEUED', 'FAILED') OR (state = 'UPLOADING' AND updatedAt < :staleBeforeMs))")
    suspend fun claimForUpload(
        messageId: String,
        updatedAt: Long = System.currentTimeMillis(),
        ownerUserId: String,
        staleBeforeMs: Long = updatedAt - 120_000L
    ): Int

    @Query("UPDATE attachment_transfers SET attachmentId = :attachmentId, uploadedBytes = :uploadedBytes, updatedAt = :updatedAt, lastErrorCode = NULL WHERE messageId = :messageId AND ownerUserId = :ownerUserId AND state = 'UPLOADING'")
    suspend fun updateUploadCheckpoint(
        messageId: String,
        attachmentId: String,
        uploadedBytes: Long,
        updatedAt: Long = System.currentTimeMillis(),
        ownerUserId: String
    ): Int

    // UPLOADING 正常完成；PAUSED 允许在上传已提交后固化 READY，避免暂停竞态留下孤儿对象
    @Query("UPDATE attachment_transfers SET state = 'READY', attachmentId = :attachmentId, uploadedBytes = cipherSize, updatedAt = :updatedAt, lastErrorCode = NULL WHERE messageId = :messageId AND ownerUserId = :ownerUserId AND state IN ('UPLOADING', 'PAUSED')")
    suspend fun markReady(messageId: String, attachmentId: String, updatedAt: Long = System.currentTimeMillis(), ownerUserId: String): Int

    @Query("UPDATE attachment_transfers SET state = 'FAILED', attempts = attempts + 1, lastErrorCode = :errorCode, updatedAt = :updatedAt WHERE messageId = :messageId AND ownerUserId = :ownerUserId AND state != 'PAUSED'")
    suspend fun markFailed(messageId: String, errorCode: String, updatedAt: Long = System.currentTimeMillis(), ownerUserId: String): Int

    @Query("UPDATE attachment_transfers SET state = 'QUEUED', lastErrorCode = NULL, updatedAt = :updatedAt WHERE messageId = :messageId AND ownerUserId = :ownerUserId AND state IN ('FAILED', 'PAUSED', 'UPLOADING')")
    suspend fun resume(messageId: String, updatedAt: Long = System.currentTimeMillis(), ownerUserId: String): Int

    // 8.46 修复：worker 软退避专用——仅当仍处于 QUEUED/UPLOADING 时才回置 QUEUED。
    // 与 resume 不同，不命中 PAUSED：用户点击「暂停」的竞态窗口内，soft-fail 不能把
    // 刚被暂停的任务重新唤醒（原 resume 会把 UPLOADING/PAUSED 都置 QUEUED）。
    @Query("UPDATE attachment_transfers SET state = 'QUEUED', lastErrorCode = NULL, updatedAt = :updatedAt WHERE messageId = :messageId AND ownerUserId = :ownerUserId AND state IN ('QUEUED', 'UPLOADING')")
    suspend fun requeueForRetry(messageId: String, updatedAt: Long = System.currentTimeMillis(), ownerUserId: String): Int

    @Query("UPDATE attachment_transfers SET state = 'READY', lastErrorCode = NULL, updatedAt = :updatedAt WHERE messageId = :messageId AND ownerUserId = :ownerUserId AND state IN ('FAILED', 'PAUSED') AND attachmentId IS NOT NULL AND attachmentId != '' AND cipherSize > 0 AND uploadedBytes = cipherSize")
    suspend fun retryCompletedUpload(messageId: String, updatedAt: Long = System.currentTimeMillis(), ownerUserId: String): Int

    @Query("UPDATE attachment_transfers SET state = 'PAUSED', updatedAt = :updatedAt WHERE messageId = :messageId AND ownerUserId = :ownerUserId AND state IN ('QUEUED', 'UPLOADING')")
    suspend fun pause(messageId: String, updatedAt: Long = System.currentTimeMillis(), ownerUserId: String): Int

    @Query("UPDATE attachment_transfers SET state = 'SENDING', updatedAt = :updatedAt WHERE messageId = :messageId AND ownerUserId = :ownerUserId AND attachmentId IS NOT NULL AND attachmentId != '' AND cipherSize > 0 AND uploadedBytes = cipherSize AND state IN ('READY', 'FAILED')")
    suspend fun claimForSending(messageId: String, updatedAt: Long = System.currentTimeMillis(), ownerUserId: String): Int

    @Query("UPDATE attachment_transfers SET wireContent = :wireContent, updatedAt = :updatedAt WHERE messageId = :messageId AND ownerUserId = :ownerUserId AND state = 'SENDING'")
    suspend fun storeWireContent(messageId: String, wireContent: String, updatedAt: Long = System.currentTimeMillis(), ownerUserId: String): Int

    @Query("UPDATE attachment_transfers SET state = 'QUEUED', attachmentId = NULL, wireContent = NULL, uploadedBytes = 0, attempts = attempts + 1, lastErrorCode = :errorCode, updatedAt = :updatedAt WHERE messageId = :messageId AND ownerUserId = :ownerUserId")
    suspend fun resetForReupload(messageId: String, errorCode: String, updatedAt: Long = System.currentTimeMillis(), ownerUserId: String): Int

    @Query(
        """
        UPDATE attachment_transfers
        SET state = 'READY', wireContent = NULL, updatedAt = :updatedAt
        WHERE messageId = :messageId
          AND ownerUserId = :ownerUserId
          AND state = 'SENDING'
          AND attachmentId IS NOT NULL AND attachmentId != ''
          AND cipherSize > 0 AND uploadedBytes = cipherSize
        """
    )
    suspend fun releaseSending(messageId: String, updatedAt: Long = System.currentTimeMillis(), ownerUserId: String): Int

    @Query("UPDATE attachment_transfers SET state = 'READY', updatedAt = :updatedAt WHERE chatId = :chatId AND ownerUserId = :ownerUserId AND state = 'SENDING' AND attachmentId IS NOT NULL AND attachmentId != '' AND cipherSize > 0 AND uploadedBytes = cipherSize")
    suspend fun releaseSendingForChat(chatId: String, updatedAt: Long = System.currentTimeMillis(), ownerUserId: String): Int

    /**
     * Only reclaim stale SENDING rows after process death. Active finalize workers keep
     * updating updatedAt; releasing them mid-flight causes double-send with a new envelope.
     */
    @Query(
        """
        UPDATE attachment_transfers SET state = 'READY', wireContent = NULL, updatedAt = :updatedAt
        WHERE state = 'SENDING'
          AND attachmentId IS NOT NULL AND attachmentId != ''
          AND cipherSize > 0 AND uploadedBytes = cipherSize
          AND updatedAt < :staleBeforeMs
          AND ownerUserId = :ownerUserId
        """
    )
    suspend fun releaseStaleSending(staleBeforeMs: Long, updatedAt: Long = System.currentTimeMillis(), ownerUserId: String): Int

    /**
     * 群 SK 轮换后丢弃待发密文，并把 SENDING 降回 READY。
     * 仅清 wire 会让已 claim 的 finalize 继续用内存里的旧 epoch 密文发出去。
     */
    @Query(
        """
        UPDATE attachment_transfers
        SET wireContent = NULL,
            state = CASE WHEN state = 'SENDING' THEN 'READY' ELSE state END,
            updatedAt = :updatedAt
        WHERE chatId = :chatId
          AND ownerUserId = :ownerUserId
          AND (wireContent IS NOT NULL OR state = 'SENDING')
        """
    )
    suspend fun clearWireContentForChat(chatId: String, updatedAt: Long = System.currentTimeMillis(), ownerUserId: String): Int

    @Query("UPDATE attachment_transfers SET wireContent = NULL, updatedAt = :updatedAt WHERE messageId = :messageId AND ownerUserId = :ownerUserId")
    suspend fun clearWireContent(messageId: String, updatedAt: Long = System.currentTimeMillis(), ownerUserId: String): Int

    @Query("DELETE FROM attachment_transfers WHERE messageId = :messageId AND ownerUserId = :ownerUserId")
    suspend fun delete(messageId: String, ownerUserId: String): Int

    @Query("DELETE FROM attachment_transfers WHERE chatId = :chatId AND ownerUserId = :ownerUserId")
    suspend fun deleteByChat(chatId: String, ownerUserId: String): Int

    @Query("DELETE FROM attachment_transfers")
    suspend fun deleteAll()
}
