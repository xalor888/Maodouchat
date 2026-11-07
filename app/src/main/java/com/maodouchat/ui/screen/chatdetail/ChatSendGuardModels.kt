package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.core.model.RetryRejectReason
import com.maodouchat.core.model.Message

sealed interface RetryDecision {
    data class Allow(val message: Message) : RetryDecision
    /** 附件型失败消息：必须走附件重试，不能当文本重发。 */
    data class NeedsAttachmentRetry(val message: Message) : RetryDecision
    data class Reject(val reason: RetryRejectReason) : RetryDecision
}
