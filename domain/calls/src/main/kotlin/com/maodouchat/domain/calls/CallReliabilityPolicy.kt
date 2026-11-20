package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallType

object CallReliabilityPolicy {
    fun requiredPermissions(callType: CallType): Set<CallMediaPermission> = when (callType) {
        CallType.VIDEO -> setOf(CallMediaPermission.MICROPHONE, CallMediaPermission.CAMERA)
        CallType.AUDIO, CallType.GROUP -> setOf(CallMediaPermission.MICROPHONE)
    }

    fun shouldAcceptSignal(isGroupCall: Boolean, expectedContactId: String, fromUserId: String): Boolean {
        if (fromUserId.isBlank()) return false
        return isGroupCall || expectedContactId.isBlank() || expectedContactId == fromUserId
    }

    /** An unbound receiver may accept an initial/legacy signal; an active call requires an exact ID. */
    fun shouldAcceptCallId(activeCallId: String, incomingCallId: String): Boolean =
        activeCallId.isBlank() || activeCallId == incomingCallId

    fun normalizeSignalingType(type: String): String = type.trim().lowercase()

}

object GroupCallPolicy {
    const val MAX_MESH_MEMBERS = 6

    fun gridColumns(participantCount: Int): Int = if (participantCount <= 4) 2 else 3

    fun shouldAcceptMetadata(
        activeGroupId: String,
        incomingGroupId: String,
        activeMembers: Collection<String>,
        incomingMembers: Collection<String>
    ): Boolean {
        if (activeGroupId.isBlank() || incomingGroupId.isBlank()) return true
        return activeGroupId == incomingGroupId && activeMembers.toSet() == incomingMembers.toSet()
    }

}
