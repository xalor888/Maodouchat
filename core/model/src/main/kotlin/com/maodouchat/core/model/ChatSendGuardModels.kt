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
