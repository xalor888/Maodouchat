package com.maodouchat.domain.messaging

/** 附件传输控制器用到的文案键；app 侧由 `fileTransferTextResId` 映射到 R。 */
enum class FileTransferText {
    DOWNLOAD_FAILED,
    CONTROL_FAILED,
    SOURCE_MISSING,
    FILE_TOO_LARGE,
    REFERENCE_INVALID,
    INTEGRITY_FAILED,
    CONTENT_MISMATCH,
}
