package com.maodouchat.domain.messaging

// 文件传输操作可用性：主操作与取消按钮是否出现；图标与文案映射留在 app 侧。
// PREPARING 只有取消（主操作是进度圈）；未知/null 状态与原 else 一致：无主操作、无取消。
enum class FileTransferPrimaryAction {
    PAUSE,
    RESUME,
    RETRY,
    PROGRESS,
}

fun fileTransferPrimaryAction(state: String?): FileTransferPrimaryAction =
    when (state) {
        "QUEUED", "UPLOADING" -> FileTransferPrimaryAction.PAUSE
        "PAUSED" -> FileTransferPrimaryAction.RESUME
        "FAILED" -> FileTransferPrimaryAction.RETRY
        else -> FileTransferPrimaryAction.PROGRESS
    }

fun fileTransferCanCancel(state: String?): Boolean =
    state in setOf("PREPARING", "QUEUED", "UPLOADING", "PAUSED", "FAILED")
