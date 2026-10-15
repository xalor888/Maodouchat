package com.maodouchat.ui.screen.contacts

internal enum class RejectReason {
    DISABLED,
    NO_SESSION,
}

internal sealed interface Decision {
    data class Reject(val reason: RejectReason) : Decision
    data class Allow(
        /** 二维码编码的目标 userId（不是昵称）。 */
        val targetUserId: String,
        val displayName: String,
        val avatar: String?,
    ) : Decision
}

internal data class FinalizeResult(
    val success: Boolean,
    val bitmap: android.graphics.Bitmap?,
    val message: String?,
)
