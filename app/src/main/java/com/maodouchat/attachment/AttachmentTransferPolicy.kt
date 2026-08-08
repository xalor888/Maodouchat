package com.maodouchat.attachment

import com.maodouchat.data.local.entity.AttachmentTransferEntity
import com.maodouchat.data.local.entity.AttachmentTransferState
import com.maodouchat.data.local.entity.hasCompletedUpload

enum class AttachmentWorkerAction {
    STOP,
    FAIL,
    UPLOAD,
    FINALIZE,
    PROMOTE_AND_FINALIZE
}

fun AttachmentTransferEntity.nextWorkerAction(): AttachmentWorkerAction = when (state) {
    AttachmentTransferState.PREPARING,
    AttachmentTransferState.PAUSED,
    // SENDING 由 claim 持有；进程死后 reconcile 只 releaseStaleSending → READY 再调度
    AttachmentTransferState.SENDING -> AttachmentWorkerAction.STOP
    AttachmentTransferState.READY -> if (hasCompletedUpload()) {
        AttachmentWorkerAction.FINALIZE
    } else {
        AttachmentWorkerAction.FAIL
    }
    AttachmentTransferState.FAILED -> if (hasCompletedUpload()) {
        AttachmentWorkerAction.PROMOTE_AND_FINALIZE
    } else {
        AttachmentWorkerAction.UPLOAD
    }
    AttachmentTransferState.QUEUED,
    AttachmentTransferState.UPLOADING -> AttachmentWorkerAction.UPLOAD
    else -> AttachmentWorkerAction.FAIL
}

fun AttachmentTransferEntity.shouldScheduleAfterProcessDeath(): Boolean = when (state) {
    AttachmentTransferState.QUEUED,
    AttachmentTransferState.UPLOADING -> true
    // PREPARING / PAUSED / SENDING：死后不直接排期。
    // reconcile 先 releaseStaleSending → READY，再由 READY 分支调度 finalize。
    AttachmentTransferState.READY -> hasCompletedUpload()
    AttachmentTransferState.FAILED -> hasCompletedUpload()
    else -> false
}
