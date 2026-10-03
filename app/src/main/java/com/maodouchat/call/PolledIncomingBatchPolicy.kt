package com.maodouchat.call

import com.maodouchat.webrtc.CallType
import com.maodouchat.webrtc.WebRTCSignaling.SignalMessage

object PolledIncomingBatchPolicy {

    /** 未接来电墓碑的参数：调用方用它调 MissedCallRecorder（带会话门禁的写路径）。 */
    data class MissedRecordParams(
        val signalingCallId: String,
        val fromUserId: String,
        val callerName: String,
        val isVideo: Boolean,
        val isGroup: Boolean,
    )

    data class TerminalDecision(
        /** 必须取消该 callId 的来电通知，并向 CallActionBus 转发 hang-up。 */
        val callId: String,
        /** 终端命中了本地 pending 的响铃：清 pending 并记未接来电墓碑；null = 只取消+转发。 */
        val missedRecord: MissedRecordParams?,
    )

    data class Decision(
        val terminals: List<TerminalDecision>,
        /** preferCallId 已被终端覆盖或库中已无其 offer（幽灵响铃）：取消该通知。 */
        val cancelPreferCallId: Boolean,
        /** 首选 offer；null = 无可响铃 offer。 */
        val primary: SignalMessage?,
        /** 是否走导航接线；false = WS 路径已送达同 callId，轮询不再重复响铃。 */
        val shouldNavigate: Boolean,
        /** 其余 offer：逐条回 busy。 */
        val busyReplies: List<SignalMessage>,
    )

    fun decide(
        messages: List<SignalMessage>,
        preferCallId: String,
        existingPending: IncomingCallCoordinator.PendingIncomingCall?,
        nowMs: Long,
    ): Decision {
        val terminals = messages.filter { CallOfferSelector.isTerminalType(it.type) }
        val terminatedCallIds = CallOfferSelector.terminatedCallIds(messages)
        val terminalDecisions = terminals.mapNotNull { terminal ->
            if (terminal.callId.isBlank()) return@mapNotNull null
            val pending = existingPending
            // 只有「对方挂断」才记未接：busy/reject 走各自的收尾语义（与
            // MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed 的 RINGING 守卫一致）。
            val missed = if (
                pending != null &&
                pending.callId == terminal.callId &&
                terminal.type.equals("hang-up", ignoreCase = true)
            ) {
                MissedRecordParams(
                    signalingCallId = terminal.callId.ifBlank { pending.callId },
                    fromUserId = pending.contactId.ifBlank { terminal.fromUserId },
                    callerName = pending.contactName,
                    isVideo = pending.callType == CallType.VIDEO,
                    isGroup = pending.groupId.isNotBlank(),
                )
            } else {
                null
            }
            TerminalDecision(callId = terminal.callId, missedRecord = missed)
        }
        // FCM 指定 callId 已被终端覆盖：不要再响铃。
        val preferKilled = preferCallId.isNotBlank() && preferCallId in terminatedCallIds
        val offers = messages.filter {
            CallOfferSelector.isDirectRingOffer(it.groupId, it.groupInvite) &&
                SignalingOfferFreshnessPolicy.shouldKeepOffer(
                    type = it.type,
                    callId = it.callId,
                    terminatedCallIds = terminatedCallIds,
                    timestampMillis = it.timestamp,
                    nowMillis = nowMs,
                )
        }
        // FCM 唤醒但库中已无该 call 的 offer（已挂断/已消费）：防幽灵响铃。
        val ghostPrefer = preferCallId.isNotBlank() && offers.none { it.callId == preferCallId }
        if (offers.isEmpty()) {
            return Decision(
                terminals = terminalDecisions,
                cancelPreferCallId = preferKilled || ghostPrefer,
                primary = null,
                shouldNavigate = false,
                busyReplies = emptyList(),
            )
        }
        val (primary, rest) = CallOfferSelector.selectPrimary(offers, preferCallId)
        // 双通道去重（8.35）：WS 已送达的同 callId offer（或空 callId 时同联系人）已 pending
        // 响铃时，轮询不再重复导航/派发系统来电，避免重复响铃与 30s 计时被重置。
        val alreadyHandled = existingPending != null && (
            (primary.callId.isNotBlank() && existingPending.callId == primary.callId) ||
                (primary.callId.isBlank() && existingPending.contactId == primary.fromUserId)
            )
        return Decision(
            terminals = terminalDecisions,
            cancelPreferCallId = preferKilled || ghostPrefer,
            primary = primary,
            shouldNavigate = !alreadyHandled,
            busyReplies = rest,
        )
    }
}
