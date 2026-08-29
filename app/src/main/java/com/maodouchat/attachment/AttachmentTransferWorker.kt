package com.maodouchat.attachment

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.maodouchat.MaodouchatApp
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.hasCompletedUpload
import com.maodouchat.network.ApiException
import com.maodouchat.network.ApiFailureKind
import com.maodouchat.network.ApiService
import com.maodouchat.network.TokenManager
import kotlinx.coroutines.CancellationException
import java.io.File

class AttachmentTransferWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val messageId = inputData.getString(AttachmentTransferScheduler.KEY_MESSAGE_ID)
            ?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        val expectedOwnerUserId = inputData.getString(AttachmentTransferScheduler.KEY_OWNER_USER_ID)
            ?.takeIf(String::isNotBlank)
            ?: return Result.failure()
        // 9.302：诊断日志——定位发图 worker 被取消/未重调度问题
        Log.i(TAG, "doWork start: $messageId attempt=$runAttemptCount")
        val app = applicationContext as? MaodouchatApp ?: return Result.failure()
        val dao = app.database.attachmentTransferDao()
        if (app.database.messagingV2Dao().isMessageTerminal(expectedOwnerUserId, messageId)) {
            AttachmentTransferCoordinator.discardTerminal(app, messageId, expectedOwnerUserId)
            return Result.success()
        }
        val tokenManager = TokenManager.getInstance(applicationContext)
        val ownerBeforeTokenRead = tokenManager.getUserId().orEmpty()
        if (ownerBeforeTokenRead != expectedOwnerUserId) return Result.success()
        val token = tokenManager.getToken().orEmpty()
        if (tokenManager.getUserId().orEmpty() != expectedOwnerUserId) return Result.success()
        // 8.34 修复：token 空时此前无限 Result.retry()（10s 起指数退避无上限）——会话异常
        // （token 被清空但 userId 仍匹配）会永久重试、反复唤醒进程。有界重试后按失败终止。
        if (token.isBlank()) {
            if (runAttemptCount >= MAX_RETRIES) return Result.failure()
            return Result.retry()
        }
        val transfer = dao.get(messageId, ownerUserId = expectedOwnerUserId) ?: return Result.success()
        if (!sessionActive(tokenManager, expectedOwnerUserId)) return Result.success()
        val workerAction = transfer.nextWorkerAction()
        if (workerAction == AttachmentWorkerAction.STOP) return Result.success()
        if (workerAction == AttachmentWorkerAction.FAIL) {
            if (!sessionActive(tokenManager, expectedOwnerUserId)) return Result.success()
            dao.markFailed(messageId, ERROR_INVALID_STATE, ownerUserId = expectedOwnerUserId)
            return Result.failure()
        }

        if (workerAction == AttachmentWorkerAction.FINALIZE) {
            return finalizeReady(dao, messageId, expectedOwnerUserId)
        }
        if (workerAction == AttachmentWorkerAction.PROMOTE_AND_FINALIZE) {
            // 已完成上传的任务绝不能 fall-through 到重新上传路径
            if (!sessionActive(tokenManager, expectedOwnerUserId)) return Result.success()
            if (dao.retryCompletedUpload(messageId, ownerUserId = expectedOwnerUserId) == 1) {
                return finalizeReady(dao, messageId, expectedOwnerUserId)
            }
            if (!sessionActive(tokenManager, expectedOwnerUserId)) return Result.success()
            val current = dao.get(messageId, ownerUserId = expectedOwnerUserId) ?: return Result.success()
            if (!sessionActive(tokenManager, expectedOwnerUserId)) return Result.success()
            return when (current.state) {
                AttachmentTransferState.READY, AttachmentTransferState.SENDING -> finalizeReady(dao, messageId, expectedOwnerUserId)
                AttachmentTransferState.PAUSED -> Result.success()
                else -> {
                    // 仍标记为已完成上传但 promote 失败：保持失败态等待下次校准，禁止重复上传
                    if (current.hasCompletedUpload()) Result.success() else Result.retry()
                }
            }
        }

        val encryptedFile = File(transfer.encryptedPath)
        val uploadRoot = File(applicationContext.cacheDir, "attachment-uploads")
        val validPath = runCatching {
            encryptedFile.canonicalPath.startsWith(uploadRoot.canonicalPath + File.separator)
        }.getOrDefault(false)
        if (!validPath || !encryptedFile.isFile || encryptedFile.length() != transfer.cipherSize) {
            if (!sessionActive(tokenManager, expectedOwnerUserId)) return Result.success()
            dao.markFailed(messageId, ERROR_SOURCE_MISSING, ownerUserId = expectedOwnerUserId)
            return Result.failure()
        }

        if (!sessionActive(tokenManager, expectedOwnerUserId)) return Result.success()
        if (dao.claimForUpload(messageId, ownerUserId = expectedOwnerUserId) != 1) {
            // 8.60：claim 失败 ≠ 任务结束——进程死亡后 2min 内重启，行 updatedAt 仍新鲜无法重领。
            // 重读行：仍处待上传态则 retry()（WorkManager 指数退避），待 stale 窗口过期后重领，
            // 否则该行永久停在 UPLOADING 转圈
            if (!sessionActive(tokenManager, expectedOwnerUserId)) return Result.success()
            val pending = dao.get(messageId, ownerUserId = expectedOwnerUserId)
            return when (pending?.state) {
                AttachmentTransferState.QUEUED,
                AttachmentTransferState.UPLOADING,
                AttachmentTransferState.FAILED -> Result.retry()
                else -> Result.success()
            }
        }
        return try {
            ensureSessionActive(tokenManager, expectedOwnerUserId)
            val upload = ApiService.uploadEncryptedAttachment(
                token = token,
                chatId = transfer.chatId,
                messageId = messageId,
                encryptedFile = encryptedFile,
                cipherSha256 = transfer.cipherSha256,
                onCheckpoint = { attachmentId, uploadedBytes, _ ->
                    // Logout/account switch mid-upload: cancel chunk loop before more REST.
                    ensureSessionActive(tokenManager, expectedOwnerUserId)
                    val current = dao.get(messageId, ownerUserId = expectedOwnerUserId)
                        ?: throw CancellationException("attachment_transfer_deleted")
                    ensureSessionActive(tokenManager, expectedOwnerUserId)
                    if (current.state == AttachmentTransferState.PAUSED) {
                        throw CancellationException("attachment_transfer_paused")
                    }
                    if (dao.updateUploadCheckpoint(messageId, attachmentId, uploadedBytes, ownerUserId = expectedOwnerUserId) != 1) {
                        throw CancellationException("attachment_transfer_not_uploading")
                    }
                }
            ).getOrThrow()
            ensureSessionActive(tokenManager, expectedOwnerUserId)
            val current = dao.get(messageId, ownerUserId = expectedOwnerUserId)
            ensureSessionActive(tokenManager, expectedOwnerUserId)
            // 上传已完成：即使竞态进入 PAUSED，也要把对象 ID 固化到 READY，避免孤儿对象与卡死
            if (dao.markReady(messageId, upload.id, ownerUserId = expectedOwnerUserId) != 1) {
                ensureSessionActive(tokenManager, expectedOwnerUserId)
                val after = dao.get(messageId, ownerUserId = expectedOwnerUserId)
                ensureSessionActive(tokenManager, expectedOwnerUserId)
                if (after?.state == AttachmentTransferState.READY || after?.state == AttachmentTransferState.SENDING) {
                    return finalizeReady(dao, messageId, expectedOwnerUserId)
                }
                if (after?.state == AttachmentTransferState.PAUSED && after.hasCompletedUpload()) {
                    return Result.success()
                }
                return Result.retry()
            }
            // 若用户在 markReady 前暂停，READY 仍应继续 finalize；暂停只作用于上传阶段
            if (current?.state == AttachmentTransferState.PAUSED) {
                return finalizeReady(dao, messageId, expectedOwnerUserId)
            }
            finalizeReady(dao, messageId, expectedOwnerUserId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (!sessionActive(tokenManager, expectedOwnerUserId)) {
                throw CancellationException("attachment_session_changed").apply { initCause(error) }
            }
            val code = error.transferErrorCode()
            Log.w(TAG, "Attachment transfer failed: $messageId ($code)", error)
            if (runAttemptCount < MAX_RETRIES && error.isRetryableTransferError()) {
                // Soft-fail: leave QUEUED/UPLOADING (or requeue) without FAILED flash mid-retry.
                // claimForUpload already set UPLOADING; release back to QUEUED so next attempt can claim.
                // 8.46：用 requeueForRetry（不含 PAUSED）——用户点击暂停的竞态窗口内，
                // 不能把刚被暂停的任务重新置 QUEUED 唤醒。
                val row = dao.get(messageId, ownerUserId = expectedOwnerUserId)
                ensureSessionActive(tokenManager, expectedOwnerUserId)
                if (row?.state == AttachmentTransferState.UPLOADING || row?.state == AttachmentTransferState.QUEUED) {
                    ensureSessionActive(tokenManager, expectedOwnerUserId)
                    dao.requeueForRetry(messageId, ownerUserId = expectedOwnerUserId)
                }
                Result.retry()
            } else {
                ensureSessionActive(tokenManager, expectedOwnerUserId)
                dao.markFailed(messageId, code, ownerUserId = expectedOwnerUserId)
                Result.failure()
            }
        }
    }

    private suspend fun finalizeReady(
        dao: com.maodouchat.data.local.dao.AttachmentTransferDao,
        messageId: String,
        ownerUserId: String
    ): Result = when (
        val outcome = AttachmentTransferFinalizer.finalize(applicationContext, messageId, ownerUserId)
    ) {
        is AttachmentFinalizeOutcome.Sent,
        AttachmentFinalizeOutcome.AlreadyClaimed,
        AttachmentFinalizeOutcome.DiscardedTerminal -> Result.success()
        // SK 轮换清 wire / 弱网：保持 READY 或已释放 claim，worker 重试
        AttachmentFinalizeOutcome.ClaimInvalidated,
        AttachmentFinalizeOutcome.ReuploadRequired,
        is AttachmentFinalizeOutcome.Transient -> {
            // 8.48 修复 HIGH：finalize 重试无上限（与上传路径 MAX_RETRIES 不一致）——
            // 永久性故障（服务端持续 5xx）会让消息永远停在 SENDING、transfer 永远 READY，
            // 进程被反复唤醒。达上限后标 FAILED + Result.failure()。
            if (runAttemptCount >= MAX_RETRIES) {
                try {
                    dao.markFailed(messageId, "finalize_retry_exhausted", ownerUserId = ownerUserId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // 标记失败为尽力而为；Result.failure() 仍会终止本轮重试
                }
                Result.failure()
            } else {
                Result.retry()
            }
        }
        is AttachmentFinalizeOutcome.Failed -> Result.failure()
    }

    private fun Throwable.transferErrorCode(): String = when (this) {
        is ApiException -> "${kind.name}_${statusCode ?: 0}"
        else -> this::class.java.simpleName.take(80).ifBlank { "UNKNOWN" }
    }

    private fun Throwable.isRetryableTransferError(): Boolean = when (this) {
        is ApiException -> retryAfterSeconds != null ||
            kind in setOf(ApiFailureKind.NETWORK, ApiFailureKind.TIMEOUT) ||
            statusCode == 429 ||
            (statusCode ?: 0) >= 500
        else -> this is java.io.IOException
    }

    private fun sessionActive(tokenManager: TokenManager, expectedOwnerUserId: String): Boolean =
        com.maodouchat.security.BackgroundSessionGate.mayContinue(
            expectedUserId = expectedOwnerUserId,
            liveToken = tokenManager.getToken(),
            liveUserId = tokenManager.getUserId(),
        )

    private fun ensureSessionActive(tokenManager: TokenManager, expectedOwnerUserId: String) {
        if (!sessionActive(tokenManager, expectedOwnerUserId)) {
            throw CancellationException("attachment_session_changed")
        }
    }

    private companion object {
        const val TAG = "AttachmentTransferWorker"
        const val ERROR_SOURCE_MISSING = "SOURCE_MISSING"
        const val ERROR_INVALID_STATE = "INVALID_STATE"
        // 9.3xx：4→6——服务端限流窗口（429 Retry-After ≤60s）内不再烧光重试次数直接标 FAILED
        const val MAX_RETRIES = 6
    }
}
