package com.maodouchat.call

import com.maodouchat.webrtc.CallReliabilityPolicy
import com.maodouchat.webrtc.GroupCallPolicy

/**
 * P03：入站通话信令纯准入（对端、callId、群元数据、有序游标）。
 * 幂等去重由 [CallSignalingIdempotencyStore] 承接，不在此判定。
 */
object CallSignalingAdmissionPolicy {

    fun admit(
        isGroupCall: Boolean,
        expectedContactId: String,
        fromUserId: String,
        activeCallId: String,
        incomingCallId: String,
        activeGroupId: String,
        incomingGroupId: String,
        activeMembers: Collection<String>,
        incomingMembers: Collection<String>,
        signalType: String,
        incomingEpoch: Long = 0L,
        incomingSequence: Long = 0L,
        lastAcceptedCursor: Cursor? = null,
    ): Decision {
        if (!CallReliabilityPolicy.shouldAcceptSignal(isGroupCall, expectedContactId, fromUserId)) {
            return Decision.DROP
        }
        val normalized = CallReliabilityPolicy.normalizeSignalingType(signalType)
        if (!GroupCallPolicy.shouldAcceptMetadata(
                activeGroupId,
                incomingGroupId,
                activeMembers,
                incomingMembers,
            )
        ) {
            return if (normalized == "offer") Decision.BUSY_REJECT else Decision.DROP
        }
        if (!CallReliabilityPolicy.shouldAcceptCallId(activeCallId, incomingCallId)) {
            return if (normalized == "offer") Decision.BUSY_REJECT else Decision.DROP
        }
        return when (
            CallSignalingOrderPolicy.admit(
                Cursor(incomingEpoch, incomingSequence),
                lastAcceptedCursor,
            )
        ) {
            Admit.Accept -> Decision.ACCEPT
            Admit.RejectStale -> Decision.DROP
        }
    }
}
