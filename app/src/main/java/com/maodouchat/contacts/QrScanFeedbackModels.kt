package com.maodouchat.contacts

enum class Kind {
    INVALID_PAYLOAD,
    SESSION_EXPIRED,
    USER_NOT_FOUND,
    INVITE_INVALID_OR_EXPIRED,
    INVITE_BLOCKED,
    GROUP_FULL,
    NETWORK,
    UNKNOWN
}

data class Feedback(
    val kind: Kind,
    /** Prefer serverCode when present for support/debug; UI may ignore. */
    val serverCode: String? = null,
    val retryable: Boolean = false
)
