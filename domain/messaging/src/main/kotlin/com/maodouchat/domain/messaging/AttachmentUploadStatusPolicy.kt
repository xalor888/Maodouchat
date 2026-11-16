package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AttachmentUploadStatusResponse

// 附件分片上传状态校验：服务端回的状态必须与本地会话一致，否则 require 抛错。
object AttachmentUploadStatusPolicy {

    // 与 ApiService.ATTACHMENT_ID_REGEX 同值。
    private val ATTACHMENT_ID_REGEX = Regex("^att_[A-Za-z0-9_-]{20,100}$")

    fun validate(
        status: AttachmentUploadStatusResponse,
        expectedSha256: String,
        expectedSize: Long
    ) {
        require(status.id.matches(ATTACHMENT_ID_REGEX)) { "attachment_id_invalid" }
        require(status.cipherSha256 == expectedSha256.lowercase()) { "attachment_hash_mismatch" }
        require(status.cipherSize == expectedSize) { "attachment_size_mismatch" }
        require(status.uploadedBytes in 0L..expectedSize) { "attachment_offset_invalid" }
        require(status.status in setOf("UPLOADING", "UPLOADED", "COMMITTED")) { "attachment_status_invalid" }
        require(!status.complete || status.uploadedBytes == expectedSize) { "attachment_completion_invalid" }
    }
}
