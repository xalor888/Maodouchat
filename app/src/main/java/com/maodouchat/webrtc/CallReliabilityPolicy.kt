package com.maodouchat.webrtc

import java.util.concurrent.atomic.AtomicLong

enum class CallMediaPermission { MICROPHONE, CAMERA }

object CallReliabilityPolicy {
    /** ICE DISCONNECTED grace before treating the call as failed (ms). */
    const val ICE_RECONNECT_GRACE_MS = 10_000L

    /** ICE FAILED 后最多尝试 restartIce 重连次数；超过则结束通话。 */
    const val ICE_MAX_RESTART_ATTEMPTS = 2

    /** 两次 ICE restart 之间的间隔（ms），给 WebRTC 足够时间收集新候选。 */
    const val ICE_RESTART_INTERVAL_MS = 5_000L

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

    /** User actions must always name the exact active call; unlike initial signaling they cannot bind a session. */
    fun shouldAcceptHangUpAction(activeCallId: String, requestedCallId: String): Boolean =
        activeCallId == requestedCallId

    fun normalizeSignalingType(type: String): String = type.trim().lowercase()

    private val CRITICAL_SIGNALING_TYPES = setOf("offer", "answer", "reject", "busy", "hang-up")

    fun isCriticalSignalingType(type: String): Boolean =
        normalizeSignalingType(type) in CRITICAL_SIGNALING_TYPES

    /**
     * ICE state transitions for the single reconnect grace window.
     * - DISCONNECTED -> start/refresh grace (ICE restart 已在 WebRTCManager 内自动触发)
     * - CONNECTED/COMPLETED -> cancel grace + reset restart counter
     * - FAILED -> 尝试 ICE restart 重连（最多 ICE_MAX_RESTART_ATTEMPTS 次），超过才 END_NOW
     */
    fun iceReconnectAction(iceState: String, restartAttempts: Int = 0): IceReconnectAction = when (iceState.uppercase()) {
        "DISCONNECTED" -> IceReconnectAction.START_GRACE
        "CONNECTED", "COMPLETED" -> IceReconnectAction.CANCEL_GRACE
        "FAILED" -> if (restartAttempts < ICE_MAX_RESTART_ATTEMPTS) IceReconnectAction.RESTART_ICE else IceReconnectAction.END_NOW
        else -> IceReconnectAction.IGNORE
    }
}

enum class IceReconnectAction {
    START_GRACE,
    CANCEL_GRACE,
    RESTART_ICE,
    END_NOW,
    IGNORE
}

/**
 * Maps RTT + packet loss into UI quality buckets (W3-03).
 * Null RTT or missing sample → UNKNOWN (still measuring).
 */
object CallNetworkQualityPolicy {
    enum class Level { GOOD, FAIR, POOR, UNKNOWN }

    fun fromStats(rttMs: Long?, packetLossPercent: Double?): Level {
        if (rttMs == null) return Level.UNKNOWN
        val loss = packetLossPercent
        return when {
            loss != null && rttMs <= 200 && loss <= 1.0 -> Level.GOOD
            loss != null && rttMs <= 500 && loss <= 4.0 -> Level.FAIR
            loss == null && rttMs <= 200 -> Level.GOOD
            loss == null && rttMs <= 500 -> Level.FAIR
            else -> Level.POOR
        }
    }
}

/** Rejects native callbacks and asynchronous failures from a released call generation. */
internal class CallSessionGate {
    private val generation = AtomicLong(0L)

    fun begin(): Long = generation.incrementAndGet()

    fun invalidate(session: Long): Boolean =
        generation.compareAndSet(session, session + 1L)

    fun isCurrent(session: Long): Boolean = generation.get() == session
}

object GroupCallPolicy {
    const val MAX_MESH_MEMBERS = 6

    fun isActive(state: GroupPeerConnectionState): Boolean =
        state == GroupPeerConnectionState.CONNECTING ||
            state == GroupPeerConnectionState.CONNECTED ||
            state == GroupPeerConnectionState.RECONNECTING

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

    /** Exactly one side creates an offer for each mesh edge, preventing SDP glare. */
    fun shouldInitiateMeshEdge(selfUserId: String, peerUserId: String): Boolean =
        selfUserId.isNotBlank() && peerUserId.isNotBlank() && selfUserId < peerUserId
}
