package com.maodouchat.domain.messaging

import com.maodouchat.core.crypto.Reason

// 发送者密钥分发原因→文案种类；R.string 映射留在 app 侧。
enum class SenderKeyReasonLabel {
    LOCAL_MISSING,
    EPOCH_MISMATCH,
    FAILED_TARGETS,
    PENDING_TARGETS,
    UNKNOWN_TARGETS,
    NO_SERVER_RECORD,
    COMPLETE,
}

fun senderKeyReasonLabelKind(reason: Reason): SenderKeyReasonLabel =
    when (reason) {
        Reason.LOCAL_MISSING -> SenderKeyReasonLabel.LOCAL_MISSING
        Reason.EPOCH_MISMATCH -> SenderKeyReasonLabel.EPOCH_MISMATCH
        Reason.FAILED_TARGETS -> SenderKeyReasonLabel.FAILED_TARGETS
        Reason.PENDING_TARGETS -> SenderKeyReasonLabel.PENDING_TARGETS
        Reason.UNKNOWN_TARGETS -> SenderKeyReasonLabel.UNKNOWN_TARGETS
        Reason.NO_SERVER_RECORD -> SenderKeyReasonLabel.NO_SERVER_RECORD
        Reason.COMPLETE -> SenderKeyReasonLabel.COMPLETE
    }

// 分发目标状态字符串→文案种类；未知状态原样回显，归为 OTHER。
enum class SenderKeyStatusLabel {
    SENT,
    FAILED,
    PENDING,
    OTHER,
}

fun senderKeyStatusLabelKind(status: String): SenderKeyStatusLabel =
    when (status.uppercase()) {
        "SENT" -> SenderKeyStatusLabel.SENT
        "FAILED" -> SenderKeyStatusLabel.FAILED
        "PENDING" -> SenderKeyStatusLabel.PENDING
        else -> SenderKeyStatusLabel.OTHER
    }
