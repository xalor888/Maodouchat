package com.maodouchat.ui.screen.call

import com.maodouchat.call.CallSessionMachine
import com.maodouchat.webrtc.CallSessionGate
import com.maodouchat.webrtc.WebRTCManager
import java.util.UUID

// 通话会话簇：从 CallViewModel 纯搬移——会话世代开启/校验、callId 生成。
// VM 只留同签名委托。
internal class CallSessionController(
    private val callSessionGate: CallSessionGate,
    private val callSessionMachine: CallSessionMachine,
    private val resetIceRecoveryAttempts: () -> Unit,
    private val clearSignalingIdempotency: () -> Unit,
    private val clearInboundSignalingCursors: () -> Unit,
    private val beginOutboundSignalingCursor: () -> Unit,
    private val setActiveDomainSession: (Long) -> Unit,
    private val setActiveCallSession: (Long) -> Unit,
    private val endingCall: () -> Boolean,
    private val webRTCManager: () -> WebRTCManager?,
) {
    fun beginCallSession(peerId: String, incoming: Boolean = false): Long {
        clearSignalingIdempotency()
        clearInboundSignalingCursors()
        beginOutboundSignalingCursor()
        resetIceRecoveryAttempts()
        val snapshot = if (incoming) {
            callSessionMachine.beginIncoming(peerId)
        } else {
            callSessionMachine.beginOutgoing(peerId)
        }
        setActiveDomainSession(snapshot.epoch)
        return callSessionGate.begin().also { setActiveCallSession(it) }
    }

    fun isCurrentCallSession(session: Long, manager: WebRTCManager? = null): Boolean =
        callSessionGate.isCurrent(session) && !endingCall() && (manager == null || webRTCManager() === manager)

    fun newCallId(): String = "call_${UUID.randomUUID().toString().replace("-", "")}"
}
