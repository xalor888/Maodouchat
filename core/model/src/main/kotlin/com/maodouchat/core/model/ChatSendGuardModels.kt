package com.maodouchat.core.model

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
