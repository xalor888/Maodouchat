package com.maodouchat.domain.messaging

import kotlinx.coroutines.flow.Flow

/** 附件传输状态（M07）：准备→上传→(暂停/恢复)→finalize→提交。 */
enum class TransferStatus { PREPARING, UPLOADING, PAUSED, FINALIZING, COMMITTED, FAILED, CANCELLED }

/**
 * 附件传输状态机（纯逻辑，无 Android/网络依赖）。
 * 覆盖 pause/resume/cancel、失败重试、终态不可回退。
 */
object AttachmentTransferStateMachine {
    fun canTransition(from: TransferStatus, to: TransferStatus): Boolean = when (from) {
        TransferStatus.PREPARING -> to in setOf(TransferStatus.UPLOADING, TransferStatus.FAILED, TransferStatus.CANCELLED)
        TransferStatus.UPLOADING -> to in setOf(TransferStatus.PAUSED, TransferStatus.FINALIZING, TransferStatus.FAILED, TransferStatus.CANCELLED)
        TransferStatus.PAUSED -> to in setOf(TransferStatus.UPLOADING, TransferStatus.FAILED, TransferStatus.CANCELLED)
        TransferStatus.FINALIZING -> to in setOf(TransferStatus.COMMITTED, TransferStatus.FAILED)
        TransferStatus.COMMITTED -> false
        TransferStatus.FAILED -> to == TransferStatus.PREPARING // 重试从准备开始
        TransferStatus.CANCELLED -> false
    }

    val terminal = setOf(TransferStatus.COMMITTED, TransferStatus.CANCELLED)
}

/** 附件提交意图（M07）：UI 只提交 URI，不直接加密/上传。 */
data class AttachmentIntent(
    val conversationId: String,
    val uri: String,
    val kind: AttachmentKind,
    val caption: String? = null,
    val idempotencyKey: String,
)

data class AttachmentTransfer(
    val transferId: String,
    val status: TransferStatus,
    val progressBytes: Long = 0,
    val totalBytes: Long? = null,
)

/** 附件意图控制器（M07）：UI 唯一入口。 */
interface AttachmentIntentController {
    suspend fun submit(intent: AttachmentIntent): Result<String>
    fun observe(transferId: String): Flow<AttachmentTransfer>
    suspend fun pause(transferId: String): Boolean
    suspend fun resume(transferId: String): Boolean
    suspend fun cancel(transferId: String): Boolean
}
