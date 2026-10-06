package com.maodouchat.ui.screen.call

import com.maodouchat.R
import com.maodouchat.call.CallSignalingOutboundCursor
import com.maodouchat.webrtc.CallReliabilityPolicy
import com.maodouchat.webrtc.CallSessionGate
import com.maodouchat.webrtc.WebRTCSignaling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * 出站信令发送（offer/answer/ice-candidate/busy/hang-up…）：WS-first，
 * 关键类型按 `CallReliabilityPolicy` 始终补 REST；函数体从 `CallViewModel` 逐字搬入。
 */
internal class CallSignalingSender(
    private val scope: CoroutineScope,
    private val token: () -> String,
    private val activeCallId: () -> String,
    private val activeGroupId: () -> String,
    private val meshGroupMemberIds: () -> List<String>,
    private val activeCallSession: () -> Long,
    private val callSessionGate: CallSessionGate,
    private val outboundSignalingCursor: CallSignalingOutboundCursor,
    private val updateState: ((CallUiState) -> CallUiState) -> Unit,
    private val text: (Int, Array<out Any>) -> String,
    private val failureReason: (Throwable) -> String,
    private val onEndCall: (errorMessage: String?) -> Unit,
) {
    fun sendSdp(toUserId: String, type: String, sdp: SessionDescription, groupInvite: Boolean = false) {
        sendSignalWithFallback(
            toUserId,
            type,
            sdp.description,
            text(R.string.call_send_sdp_failed, arrayOf(type.uppercase())),
            groupInvite = groupInvite
        )
    }

    fun sendIceCandidate(toUserId: String, candidate: IceCandidate) {
        val payload = "${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}"
        sendSignalWithFallback(toUserId, "ice-candidate", payload, text(R.string.call_send_candidate_failed, emptyArray()))
    }

    fun sendSignalWithFallback(
        toUserId: String,
        type: String,
        payload: String,
        errorPrefix: String,
        callIdOverride: String? = null,
        groupIdOverride: String? = null,
        groupMemberIdsOverride: List<String>? = null,
        groupInvite: Boolean = false
    ) {
        val callId = callIdOverride ?: activeCallId()
        val groupId = groupIdOverride ?: activeGroupId()
        val groupMembers = groupMemberIdsOverride ?: meshGroupMemberIds()
        val sourceSession = activeCallSession()
        val ticket = outboundSignalingCursor.next(callId, type)
        scope.launch {
            if (token().isBlank()) {
                if (callSessionGate.isCurrent(sourceSession)) {
                    updateState { it.copy(errorMessage = text(R.string.call_session_expired, emptyArray())) }
                }
                return@launch
            }

            // 关键信令（offer/answer/hang-up 等）不能只信 OkHttp 本地 enqueue 成功；
            // WS 缓冲接受 ≠ 服务端处理。关键类型始终补 REST，ICE 保持 WS-first。
            val normalizedType = CallReliabilityPolicy.normalizeSignalingType(type)
            val sentByWebSocket = WebRTCSignaling.sendViaWebSocket(
                toUserId, type, payload, callId, groupId, groupMembers, groupInvite,
                ticket.epoch, ticket.sequence, ticket.idempotencyKey,
            )
            val needRest = CallReliabilityPolicy.isCriticalSignalingType(normalizedType) || !sentByWebSocket
            if (needRest) {
                WebRTCSignaling.sendViaRest(
                    token(), toUserId, type, payload, callId, groupId, groupMembers, groupInvite,
                    ticket.epoch, ticket.sequence, ticket.idempotencyKey,
                ).onFailure { error ->
                    if (!callSessionGate.isCurrent(sourceSession) && normalizedType != "hang-up") return@onFailure
                    val message = text(R.string.call_error_with_reason, arrayOf(errorPrefix, failureReason(error)))
                    if (
                        normalizedType == "offer" &&
                        error is WebRTCSignaling.SignalingException &&
                        error.code == "CALL_INVITE_RATE_LIMITED"
                    ) {
                        onEndCall(failureReason(error))
                    } else if (normalizedType != "hang-up") {
                        updateState { it.copy(errorMessage = message) }
                    }
                }
            }
        }
    }
}
