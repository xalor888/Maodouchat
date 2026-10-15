package com.maodouchat.ui.screen.chatdetail

internal enum class NudgeRejectReason {
    SECRET_CHAT,
    DISABLED,
    NO_CHAT,
    NO_SESSION,
}

internal sealed interface NudgeDecision {
    data class Allow(val ownerUserId: String, val chatId: String) : NudgeDecision
    data class Reject(val reason: NudgeRejectReason) : NudgeDecision
}
