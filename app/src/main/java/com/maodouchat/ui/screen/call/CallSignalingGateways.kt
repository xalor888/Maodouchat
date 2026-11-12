package com.maodouchat.ui.screen.call

import com.maodouchat.R
import com.maodouchat.core.model.CallSessionGate
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.session.CallSessionMachine
import com.maodouchat.core.session.CallSignalingOutboundCursor
import com.maodouchat.domain.calls.CallIceRecovery
import com.maodouchat.domain.calls.CallSignalingGateway
import com.maodouchat.domain.calls.CallSignalingIngressMessage
import com.maodouchat.domain.calls.CallSignalingMessage
import com.maodouchat.domain.calls.CallSignalingSender
import com.maodouchat.webrtc.WebRTCSignaling
import kotlinx.coroutines.CoroutineScope

/** 出站信令网络端口的 app 侧实现：直通 WebRTCSignaling。 */
internal class WebRtcCallSignalingGateway : CallSignalingGateway {
    override fun sendViaWebSocket(
        toUserId: String,
        type: String,
        payload: String,
        callId: String,
        groupId: String,
        groupMemberIds: List<String>,
        groupInvite: Boolean,
        epoch: Long,
        sequence: Long,
        idempotencyKey: String,
    ): Boolean = WebRTCSignaling.sendViaWebSocket(
        toUserId, type, payload, callId, groupId, groupMemberIds, groupInvite,
        epoch, sequence, idempotencyKey,
    )

    override suspend fun sendViaRest(
        token: String,
        toUserId: String,
        type: String,
        payload: String,
        callId: String,
        groupId: String,
        groupMemberIds: List<String>,
        groupInvite: Boolean,
        epoch: Long,
        sequence: Long,
        idempotencyKey: String,
    ): Result<Unit> = WebRTCSignaling.sendViaRest(
        token, toUserId, type, payload, callId, groupId, groupMemberIds, groupInvite,
        epoch, sequence, idempotencyKey,
    )
}

/** 出站信令 / ICE 恢复文案种类到 R.string 的映射。 */
internal fun callSignalingMessageResId(message: CallSignalingMessage): Int = when (message) {
    CallSignalingMessage.SEND_SDP_FAILED -> R.string.call_send_sdp_failed
    CallSignalingMessage.SEND_CANDIDATE_FAILED -> R.string.call_send_candidate_failed
    CallSignalingMessage.SESSION_EXPIRED -> R.string.call_session_expired
    CallSignalingMessage.ERROR_WITH_REASON -> R.string.call_error_with_reason
    CallSignalingMessage.NETWORK_DISCONNECTED -> R.string.call_network_disconnected
}

/** 入站信令文案种类到 R.string 的映射。 */
internal fun callSignalingIngressMessageResId(message: CallSignalingIngressMessage): Int = when (message) {
    CallSignalingIngressMessage.RATE_LIMITED -> R.string.call_rate_limited
    CallSignalingIngressMessage.FETCH_SIGNALING_FAILED -> R.string.call_fetch_signaling_failed
    CallSignalingIngressMessage.NOTIFY_BUSY_FAILED -> R.string.call_notify_busy_failed
    CallSignalingIngressMessage.PEER_REJECTED -> R.string.call_peer_rejected
    CallSignalingIngressMessage.PEER_BUSY -> R.string.call_peer_busy
    CallSignalingIngressMessage.HANDLE_SIGNALING_FAILED -> R.string.call_handle_signaling_failed
    CallSignalingIngressMessage.UNKNOWN_ERROR -> R.string.call_unknown_error
}

/** CallViewModel 的出站信令发送器接线：网络端口与文案映射由 app 侧提供。 */
internal fun createCallSignalingSender(
    scope: CoroutineScope,
    token: () -> String,
    activeCallId: () -> String,
    activeGroupId: () -> String,
    meshGroupMemberIds: () -> List<String>,
    activeCallSession: () -> Long,
    callSessionGate: CallSessionGate,
    outboundSignalingCursor: CallSignalingOutboundCursor,
    updateState: ((CallUiState) -> CallUiState) -> Unit,
    errorMessages: CallErrorMessages,
    failureReason: (Throwable) -> String,
    onEndCall: (errorMessage: String?) -> Unit,
): CallSignalingSender = CallSignalingSender(
    scope = scope,
    token = token,
    activeCallId = activeCallId,
    activeGroupId = activeGroupId,
    meshGroupMemberIds = meshGroupMemberIds,
    activeCallSession = activeCallSession,
    callSessionGate = callSessionGate,
    outboundSignalingCursor = outboundSignalingCursor,
    gateway = WebRtcCallSignalingGateway(),
    updateState = updateState,
    text = { message, args -> errorMessages.text(callSignalingMessageResId(message), *args) },
    failureReason = failureReason,
    onEndCall = onEndCall,
)

/** CallViewModel 的 ICE 恢复接线：计时器与 restartIce 由 app 侧提供。 */
internal fun createCallIceRecovery(
    scope: CoroutineScope,
    endingCall: () -> Boolean,
    callSessionMachine: CallSessionMachine,
    activeDomainSession: () -> Long,
    startTimers: () -> Unit,
    activeCallSession: () -> Long,
    callSessionGate: CallSessionGate,
    updateState: ((CallUiState) -> CallUiState) -> Unit,
    currentState: () -> CallUiState,
    onIceGiveUp: (String) -> Unit,
    errorMessages: CallErrorMessages,
    restartIce: () -> Unit,
): CallIceRecovery = CallIceRecovery(
    scope = scope,
    endingCall = endingCall,
    callSessionMachine = callSessionMachine,
    activeDomainSession = activeDomainSession,
    startTimers = startTimers,
    activeCallSession = activeCallSession,
    callSessionGate = callSessionGate,
    updateState = updateState,
    currentState = currentState,
    onIceGiveUp = onIceGiveUp,
    text = { message, args -> errorMessages.text(callSignalingMessageResId(message), *args) },
    restartIce = restartIce,
)
