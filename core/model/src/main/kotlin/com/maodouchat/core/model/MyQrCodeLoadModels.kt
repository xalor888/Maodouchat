package com.maodouchat.core.model

enum class RejectReason {
    DISABLED,
    NO_SESSION,
}

sealed interface MyQrCodeDecision {
    data class Reject(val reason: RejectReason) : MyQrCodeDecision
    data class Allow(
        /** 二维码编码的目标 userId（不是昵称）。 */
        val targetUserId: String,
        val displayName: String,
        val avatar: String?,
    ) : MyQrCodeDecision
}
