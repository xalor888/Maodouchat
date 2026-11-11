package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallReliabilityPolicy
import com.maodouchat.core.model.CallSessionGate
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.SignalingException
import com.maodouchat.core.session.CallSignalingOutboundCursor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 出站信令发送一族：从 app 侧 CallViewModel 纯搬移；VM 留同签名委托。
class CallSignalingSender(
    private val scope: CoroutineScope,
    private val token: () -> String,
    private val activeCallId: () -> String,
    private val activeGroupId: () -> String,
    private val meshGroupMemberIds: () -> List<String>,
    private val activeCallSession: () -> Long,
    private val callSessionGate: CallSessionGate,
    private val outboundSignalingCursor: CallSignalingOutboundCursor,
    private val gateway: CallSignalingGateway,
    private val updateState: ((CallUiState) -> CallUiState) -> Unit,
    private val text: (CallSignalingMessage, Array<out Any>) -> String,
    private val failureReason: (Throwable) -> String,
    private val onEndCall: (errorMessage: String?) -> Unit,
) {
    fun sendSdp(toUserId: String, type: String, sdpDescription: String, groupInvite: Boolean = false) {
        sendSignalWithFallback(
            toUserId,
            type,
            sdpDescription,
            text(CallSignalingMessage.SEND_SDP_FAILED, arrayOf(type.uppercase())),
            groupInvite = groupInvite
        )
    }

    fun sendIceCandidate(toUserId: String, sdpMid: String?, sdpMLineIndex: Int, sdp: String) {
        val payload = "${sdpMid}|${sdpMLineIndex}|${sdp}"
        sendSignalWithFallback(toUserId, "ice-candidate", payload, text(CallSignalingMessage.SEND_CANDIDATE_FAILED, emptyArray()))
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
                    updateState { it.copy(errorMessage = text(CallSignalingMessage.SESSION_EXPIRED, emptyArray())) }
                }
                return@launch
            }

            // 关键信令（offer/answer/hang-up 等）不能只信 OkHttp 本地 enqueue 成功；
            // WS 缓冲接受 ≠ 服务端处理。关键类型始终补 REST，ICE 保持 WS-first。
            val normalizedType = CallReliabilityPolicy.normalizeSignalingType(type)
            val sentByWebSocket = gateway.sendViaWebSocket(
                toUserId, type, payload, callId, groupId, groupMembers, groupInvite,
                ticket.epoch, ticket.sequence, ticket.idempotencyKey,
            )
            val needRest = CallReliabilityPolicy.isCriticalSignalingType(normalizedType) || !sentByWebSocket
            if (needRest) {
                gateway.sendViaRest(
                    token(), toUserId, type, payload, callId, groupId, groupMembers, groupInvite,
                    ticket.epoch, ticket.sequence, ticket.idempotencyKey,
                ).onFailure { error ->
                    if (!callSessionGate.isCurrent(sourceSession) && normalizedType != "hang-up") return@onFailure
                    val message = text(CallSignalingMessage.ERROR_WITH_REASON, arrayOf(errorPrefix, failureReason(error)))
                    if (
                        normalizedType == "offer" &&
                        error is SignalingException &&
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
