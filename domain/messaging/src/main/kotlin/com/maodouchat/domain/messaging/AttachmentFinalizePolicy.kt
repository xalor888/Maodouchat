package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ApiException
import com.maodouchat.core.model.AttachmentSendAfterUploadPolicy
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.RELIABLE_ATTACHMENT_TYPES

enum class AttachmentFinalizeFailure {
    ReuploadRequired,
    Transient,
    Failed,
}

/** 收尾准入：可靠类型、上传已完成、发送方即属主、消息会话与传输行会话一致。 */
fun isFinalizeEligible(
    messageType: MessageType,
    hasCompletedUpload: Boolean,
    senderId: String,
    ownerUserId: String,
    messageChatId: String,
    transferChatId: String,
): Boolean = messageType in RELIABLE_ATTACHMENT_TYPES &&
    hasCompletedUpload &&
    senderId == ownerUserId &&
    messageChatId == transferChatId

/** 收尾失败处置：未验就绪的 400/404/410 走重传；可重试走暂态；其余失败。 */
fun decideFinalizeFailure(attachmentReadyVerified: Boolean, error: Throwable): AttachmentFinalizeFailure {
    val staleUpload = !attachmentReadyVerified && error is ApiException && error.statusCode in setOf(400, 404, 410)
    return when {
        staleUpload -> AttachmentFinalizeFailure.ReuploadRequired
        AttachmentSendAfterUploadPolicy.isRetryable(error) -> AttachmentFinalizeFailure.Transient
        else -> AttachmentFinalizeFailure.Failed
    }
}

/** 收尾失败码：SEND_ + 异常类简单名，最多 60 字。 */
fun sendFailureCode(error: Throwable): String = "SEND_${error.javaClass.simpleName.take(60)}"
