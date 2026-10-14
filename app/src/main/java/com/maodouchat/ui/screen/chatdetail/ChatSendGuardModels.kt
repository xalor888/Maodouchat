package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType

enum class SendRejectReason {
    ALREADY_SENDING,
    BLANK,
    NO_SESSION,
    BLOCKED,
    MENTION_EVERYONE_FORBIDDEN,
}

enum class RetryRejectReason {
    NOT_FOUND,
    NOT_MY_MESSAGE,
    NOT_FAILED,
    NO_SESSION,
    UNSUPPORTED_TYPE,
}

sealed interface SendDecision {
    /** 放行，附带截断后的文本、组装好的 meta，以及调用方要做的 UI 动作。 */
    data class Allow(
        val text: String,
        val meta: MessageMeta,
        val messageType: MessageType,
        val clearDraft: Boolean,
        val consumeSilent: Boolean,
    ) : SendDecision

    data class Reject(val reason: SendRejectReason) : SendDecision
}

sealed interface RetryDecision {
    data class Allow(val message: Message) : RetryDecision
    /** 附件型失败消息：必须走附件重试，不能当文本重发。 */
    data class NeedsAttachmentRetry(val message: Message) : RetryDecision
    data class Reject(val reason: RetryRejectReason) : RetryDecision
}
