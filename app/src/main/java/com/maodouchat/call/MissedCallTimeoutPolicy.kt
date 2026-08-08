package com.maodouchat.call

/**
 * Pure rules for the local ringing timeout → missed-call transition.
 * Keeps NavGraph free of ad-hoc id/tray choices and makes double-fire safe.
 */
object MissedCallTimeoutPolicy {

    /**
     * Stable primary key for [com.maodouchat.data.model.MissedCall] and tray notify id.
     * Prefer the signaling [callId] so FCM incoming cancel + missed cancel share one slot
     * when possible; fall back to a synthetic id only when the offer had no callId.
     * 注意：空 callId 时用「固定前缀 + 联系人」而非时间戳——NavGraph 30s 计时器与
     * CallViewModel RINGING 超时两条路径会以各自 nowMs 生成不同 id，同一通未接来电
     * 会写入两行/两条通知；固定 id 使双路径幂等（8.35 修复）。
     */
    fun missedRecordId(
        signalingCallId: String,
        fromUserId: String,
        nowMs: Long,
    ): String {
        val callId = signalingCallId.trim()
        if (callId.isNotEmpty()) return callId
        val peer = fromUserId.trim().ifEmpty { "unknown" }
        return "mc_$peer"
    }

    /**
     * Whether the 30s local timer should still record a missed call.
     * [stillPending] must be the same object (or equal call) as [observedPending]
     * captured when the offer was accepted into the coordinator.
     */
    fun shouldRecordMissed(
        observedPending: IncomingCallCoordinator.PendingIncomingCall?,
        stillPending: IncomingCallCoordinator.PendingIncomingCall?,
    ): Boolean {
        if (observedPending == null || stillPending == null) return false
        return stillPending == observedPending ||
            (stillPending.callId.isNotBlank() &&
                stillPending.callId == observedPending.callId &&
                stillPending.contactId == observedPending.contactId)
    }

    /** Global notifications-off blocks tray; DND does not (calls are higher priority). */
    fun shouldShowTray(notificationsEnabled: Boolean): Boolean = notificationsEnabled

    /**
     * Peer cancelled (hang-up) while this device was still on the incoming ring UI.
     * Reject/busy from peer while we are callee is rare; hang-up is the normal
     * "caller gave up" path that should surface as a missed call.
     */
    fun shouldRecordPeerCancelAsMissed(
        isIncoming: Boolean,
        callStateWire: String,
    ): Boolean {
        if (!isIncoming) return false
        return callStateWire.equals("RINGING", ignoreCase = true)
    }
}
