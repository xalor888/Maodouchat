package com.maodouchat.domain.messaging

/** 附件传输 worker 动作：持久化传输行在 worker 轮询时的下一步。 */
enum class AttachmentWorkerAction {
    STOP,
    FAIL,
    UPLOAD,
    FINALIZE,
    PROMOTE_AND_FINALIZE,
}

/**
 * 附件传输 worker 决策（纯逻辑）。
 * 输入只有持久化行状态字符串与上传完成标志；未知状态一律 fail-closed。
 */
object AttachmentWorkerPolicy {

    fun hasCompletedUpload(attachmentId: String?, cipherSize: Long, uploadedBytes: Long): Boolean =
        !attachmentId.isNullOrBlank() && cipherSize > 0L && uploadedBytes == cipherSize

    fun canRetryWithoutUpload(state: String, hasCompletedUpload: Boolean): Boolean =
        state in setOf(STATE_FAILED, STATE_PAUSED) && hasCompletedUpload

    fun nextAction(state: String, hasCompletedUpload: Boolean): AttachmentWorkerAction = when (state) {
        STATE_PREPARING, STATE_PAUSED, STATE_SENDING -> AttachmentWorkerAction.STOP
        STATE_READY -> if (hasCompletedUpload) AttachmentWorkerAction.FINALIZE else AttachmentWorkerAction.FAIL
        STATE_FAILED -> if (hasCompletedUpload) AttachmentWorkerAction.PROMOTE_AND_FINALIZE else AttachmentWorkerAction.UPLOAD
        STATE_QUEUED, STATE_UPLOADING -> AttachmentWorkerAction.UPLOAD
        else -> AttachmentWorkerAction.FAIL
    }

    fun shouldScheduleAfterProcessDeath(state: String, hasCompletedUpload: Boolean): Boolean = when (state) {
        STATE_QUEUED, STATE_UPLOADING -> true
        // PREPARING / PAUSED / SENDING：死后不直接排期，reconcile 先释放 stale claim 再由 READY 分支调度。
        STATE_READY, STATE_FAILED -> hasCompletedUpload
        else -> false
    }

    private const val STATE_PREPARING = "PREPARING"
    private const val STATE_QUEUED = "QUEUED"
    private const val STATE_UPLOADING = "UPLOADING"
    private const val STATE_READY = "READY"
    private const val STATE_SENDING = "SENDING"
    private const val STATE_PAUSED = "PAUSED"
    private const val STATE_FAILED = "FAILED"
}
