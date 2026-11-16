package com.maodouchat.domain.messaging

// 文件传输状态文案种类；R.string 映射留在 app 侧；
// FAILED 下按错误码前缀细分，未知状态与原 else 一致归为 QUEUED。
enum class FileTransferStatusKind {
    PREPARING,
    QUEUED,
    UPLOADING,
    READY,
    SENDING,
    PAUSED,
    SOURCE_MISSING,
    NETWORK_FAILED,
    TIMEOUT,
    SEND_FAILED,
    FAILED,
}

fun fileTransferStatusKind(state: String, errorCode: String?): FileTransferStatusKind =
    when (state) {
        "PREPARING" -> FileTransferStatusKind.PREPARING
        "QUEUED" -> FileTransferStatusKind.QUEUED
        "UPLOADING" -> FileTransferStatusKind.UPLOADING
        "READY" -> FileTransferStatusKind.READY
        "SENDING" -> FileTransferStatusKind.SENDING
        "PAUSED" -> FileTransferStatusKind.PAUSED
        "FAILED" -> when {
            errorCode == "SOURCE_MISSING" -> FileTransferStatusKind.SOURCE_MISSING
            errorCode?.startsWith("NETWORK_") == true -> FileTransferStatusKind.NETWORK_FAILED
            errorCode?.startsWith("TIMEOUT_") == true -> FileTransferStatusKind.TIMEOUT
            errorCode?.startsWith("SEND_") == true -> FileTransferStatusKind.SEND_FAILED
            else -> FileTransferStatusKind.FAILED
        }
        else -> FileTransferStatusKind.QUEUED
    }
