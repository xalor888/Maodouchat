package com.maodouchat.attachment

import android.util.Log
import com.maodouchat.MaodouchatApp
import com.maodouchat.core.model.AttachmentTransferDisposition
import com.maodouchat.data.local.dao.AttachmentTransferDao
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.hasCompletedUpload
import com.maodouchat.domain.messaging.AttachmentFinalizeDispositionPolicy
import com.maodouchat.domain.messaging.AttachmentTransferErrorPolicy
import com.maodouchat.domain.messaging.AttachmentWorkerAction
import com.maodouchat.domain.messaging.FinalizeDisposition
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * M07：附件上传/finalize 编排用例。Worker 只调用 [execute]，不再直读全局 Application/API；
 * 全局依赖（app/tokenManager/dao/ApiService）都在本用例内封装。
 */
class AttachmentTransferUseCase(private val app: MaodouchatApp) {
    private val dao: AttachmentTransferDao = app.database.attachmentTransferDao()
    private val tokenManager = TokenManager.getInstance(app)
    private val finalizeUseCase = AttachmentFinalizeUseCase(app)

    suspend fun execute(
        messageId: String,
        ownerUserId: String,
        runAttemptCount: Int,
    ): AttachmentTransferDisposition {
        Log.i(TAG, "doWork start: $messageId attempt=$runAttemptCount")
        if (app.database.messagingV2Dao().isMessageTerminal(ownerUserId, messageId)) {
            AttachmentTransferCoordinator.discardTerminal(app, messageId, ownerUserId)
            return AttachmentTransferDisposition.SUCCESS
        }
        val ownerBeforeTokenRead = tokenManager.getUserId().orEmpty()
        if (ownerBeforeTokenRead != ownerUserId) return AttachmentTransferDisposition.SUCCESS
        val token = tokenManager.getToken().orEmpty()
        if (tokenManager.getUserId().orEmpty() != ownerUserId) return AttachmentTransferDisposition.SUCCESS
        // 8.34 修复：token 空时此前无限 Result.retry()（10s 起指数退避无上限）——会话异常
        // （token 被清空但 userId 仍匹配）会永久重试、反复唤醒进程。有界重试后按失败终止。
        if (token.isBlank()) {
            return if (runAttemptCount >= AttachmentFinalizeDispositionPolicy.MAX_TRANSFER_RETRIES) AttachmentTransferDisposition.FAILURE
            else AttachmentTransferDisposition.RETRY
        }
        val transfer = dao.get(messageId, ownerUserId = ownerUserId) ?: return AttachmentTransferDisposition.SUCCESS
        if (!sessionActive(ownerUserId)) return AttachmentTransferDisposition.SUCCESS
        val workerAction = transfer.nextWorkerAction()
        if (workerAction == AttachmentWorkerAction.STOP) return AttachmentTransferDisposition.SUCCESS
        if (workerAction == AttachmentWorkerAction.FAIL) {
            if (!sessionActive(ownerUserId)) return AttachmentTransferDisposition.SUCCESS
            dao.markFailed(messageId, AttachmentTransferErrorPolicy.ERROR_INVALID_STATE, ownerUserId = ownerUserId)
            return AttachmentTransferDisposition.FAILURE
        }

        if (workerAction == AttachmentWorkerAction.FINALIZE) {
            return finalizeReady(messageId, ownerUserId, runAttemptCount)
        }
        if (workerAction == AttachmentWorkerAction.PROMOTE_AND_FINALIZE) {
            // 已完成上传的任务绝不能 fall-through 到重新上传路径
            if (!sessionActive(ownerUserId)) return AttachmentTransferDisposition.SUCCESS
            if (dao.retryCompletedUpload(messageId, ownerUserId = ownerUserId) == 1) {
                return finalizeReady(messageId, ownerUserId, runAttemptCount)
            }
            if (!sessionActive(ownerUserId)) return AttachmentTransferDisposition.SUCCESS
            val current = dao.get(messageId, ownerUserId = ownerUserId) ?: return AttachmentTransferDisposition.SUCCESS
            if (!sessionActive(ownerUserId)) return AttachmentTransferDisposition.SUCCESS
            return when (current.state) {
                AttachmentTransferState.READY, AttachmentTransferState.SENDING -> finalizeReady(messageId, ownerUserId, runAttemptCount)
                AttachmentTransferState.PAUSED -> AttachmentTransferDisposition.SUCCESS
                else -> {
                    // 仍标记为已完成上传但 promote 失败：保持失败态等待下次校准，禁止重复上传
                    if (current.hasCompletedUpload()) AttachmentTransferDisposition.SUCCESS else AttachmentTransferDisposition.RETRY
                }
            }
        }

        val encryptedFile = File(transfer.encryptedPath)
        val uploadRoot = File(app.cacheDir, "attachment-uploads")
        val validPath = runCatching {
            encryptedFile.canonicalPath.startsWith(uploadRoot.canonicalPath + File.separator)
        }.getOrDefault(false)
        if (!validPath || !encryptedFile.isFile || encryptedFile.length() != transfer.cipherSize) {
            if (!sessionActive(ownerUserId)) return AttachmentTransferDisposition.SUCCESS
            dao.markFailed(messageId, AttachmentTransferErrorPolicy.ERROR_SOURCE_MISSING, ownerUserId = ownerUserId)
            return AttachmentTransferDisposition.FAILURE
        }

        if (!sessionActive(ownerUserId)) return AttachmentTransferDisposition.SUCCESS
        if (dao.claimForUpload(messageId, ownerUserId = ownerUserId) != 1) {
            // 8.60：claim 失败 ≠ 任务结束——进程死亡后 2min 内重启，行 updatedAt 仍新鲜无法重领。
            // 重读行：仍处待上传态则 retry()（WorkManager 指数退避），待 stale 窗口过期后重领，
            // 否则该行永久停在 UPLOADING 转圈
            if (!sessionActive(ownerUserId)) return AttachmentTransferDisposition.SUCCESS
            val pending = dao.get(messageId, ownerUserId = ownerUserId)
            return AttachmentTransferErrorPolicy.claimFailureDisposition(pending?.state)
        }
        return try {
            ensureSessionActive(ownerUserId)
            val upload = ApiService.uploadEncryptedAttachment(
                token = token,
                chatId = transfer.chatId,
                messageId = messageId,
                encryptedFile = encryptedFile,
                cipherSha256 = transfer.cipherSha256,
                onCheckpoint = { attachmentId, uploadedBytes, _ ->
                    // Logout/account switch mid-upload: cancel chunk loop before more REST.
                    ensureSessionActive(ownerUserId)
                    val current = dao.get(messageId, ownerUserId = ownerUserId)
                        ?: throw CancellationException("attachment_transfer_deleted")
                    ensureSessionActive(ownerUserId)
                    if (current.state == AttachmentTransferState.PAUSED) {
                        throw CancellationException("attachment_transfer_paused")
                    }
                    if (dao.updateUploadCheckpoint(messageId, attachmentId, uploadedBytes, ownerUserId = ownerUserId) != 1) {
                        throw CancellationException("attachment_transfer_not_uploading")
                    }
                }
            ).getOrThrow()
            ensureSessionActive(ownerUserId)
            val current = dao.get(messageId, ownerUserId = ownerUserId)
            ensureSessionActive(ownerUserId)
            // 上传已完成：即使竞态进入 PAUSED，也要把对象 ID 固化到 READY，避免孤儿对象与卡死
            if (dao.markReady(messageId, upload.id, ownerUserId = ownerUserId) != 1) {
                ensureSessionActive(ownerUserId)
                val after = dao.get(messageId, ownerUserId = ownerUserId)
                ensureSessionActive(ownerUserId)
                if (after?.state == AttachmentTransferState.READY || after?.state == AttachmentTransferState.SENDING) {
                    return finalizeReady(messageId, ownerUserId, runAttemptCount)
                }
                if (after?.state == AttachmentTransferState.PAUSED && after.hasCompletedUpload()) {
                    return AttachmentTransferDisposition.SUCCESS
                }
                return AttachmentTransferDisposition.RETRY
            }
            // 若用户在 markReady 前暂停，READY 仍应继续 finalize；暂停只作用于上传阶段
            if (current?.state == AttachmentTransferState.PAUSED) {
                return finalizeReady(messageId, ownerUserId, runAttemptCount)
            }
            finalizeReady(messageId, ownerUserId, runAttemptCount)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!sessionActive(ownerUserId)) {
                throw CancellationException("attachment_session_changed").apply { initCause(error) }
            }
            val code = AttachmentTransferErrorPolicy.transferErrorCode(error)
            Log.w(TAG, "Attachment transfer failed: $messageId ($code)", error)
            if (runAttemptCount < AttachmentFinalizeDispositionPolicy.MAX_TRANSFER_RETRIES && AttachmentTransferErrorPolicy.isRetryableTransferError(error)) {
                // Soft-fail: leave QUEUED/UPLOADING (or requeue) without FAILED flash mid-retry.
                val row = dao.get(messageId, ownerUserId = ownerUserId)
                ensureSessionActive(ownerUserId)
                if (row?.state == AttachmentTransferState.UPLOADING || row?.state == AttachmentTransferState.QUEUED) {
                    ensureSessionActive(ownerUserId)
                    dao.requeueForRetry(messageId, ownerUserId = ownerUserId)
                }
                AttachmentTransferDisposition.RETRY
            } else {
                ensureSessionActive(ownerUserId)
                dao.markFailed(messageId, code, ownerUserId = ownerUserId)
                AttachmentTransferDisposition.FAILURE
            }
        }
    }

    private suspend fun finalizeReady(
        messageId: String,
        ownerUserId: String,
        runAttemptCount: Int,
    ): AttachmentTransferDisposition = when (
        val disposition = AttachmentFinalizeDispositionPolicy.finalizeDisposition(
            finalizeUseCase.finalize(messageId, ownerUserId),
            runAttemptCount,
        )
    ) {
        FinalizeDisposition.Success -> AttachmentTransferDisposition.SUCCESS
        FinalizeDisposition.Retry -> AttachmentTransferDisposition.RETRY
        is FinalizeDisposition.Failure -> {
            if (disposition.markRetryExhausted) {
                try {
                    dao.markFailed(
                        messageId,
                        AttachmentFinalizeDispositionPolicy.FINALIZE_RETRY_EXHAUSTED_CODE,
                        ownerUserId = ownerUserId,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // 标记失败为尽力而为；FAILURE 仍会终止本轮重试
                }
            }
            AttachmentTransferDisposition.FAILURE
        }
    }

    private fun sessionActive(expectedOwnerUserId: String): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedOwnerUserId,
        )

    private fun ensureSessionActive(expectedOwnerUserId: String) {
        if (!sessionActive(expectedOwnerUserId)) {
            throw CancellationException("attachment_session_changed")
        }
    }

    private companion object {
        const val TAG = "AttachmentTransferWorker"
    }
}
