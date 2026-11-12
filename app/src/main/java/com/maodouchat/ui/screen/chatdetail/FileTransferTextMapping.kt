package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.domain.messaging.FileTransferText

/** domain 侧 FileTransferText 到 app 字符串资源的映射。 */
internal fun fileTransferTextResId(key: FileTransferText): Int = when (key) {
    FileTransferText.DOWNLOAD_FAILED -> R.string.chat_attachment_download_failed
    FileTransferText.CONTROL_FAILED -> R.string.chat_file_transfer_control_failed
    FileTransferText.SOURCE_MISSING -> R.string.chat_file_transfer_source_missing
    FileTransferText.FILE_TOO_LARGE -> R.string.chat_attachment_file_too_large
    FileTransferText.REFERENCE_INVALID -> R.string.chat_attachment_reference_invalid
    FileTransferText.INTEGRITY_FAILED -> R.string.chat_attachment_integrity_failed
    FileTransferText.CONTENT_MISMATCH -> R.string.chat_attachment_content_mismatch
}
