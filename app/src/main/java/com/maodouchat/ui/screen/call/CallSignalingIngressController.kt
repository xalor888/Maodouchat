package com.maodouchat.ui.screen.call

import android.app.Application
import android.util.Log
import com.maodouchat.R
import com.maodouchat.call.CallSignalingAdmissionPolicy
import com.maodouchat.call.CallSignalingOrderPolicy
import com.maodouchat.call.IncomingCallCoordinator
import com.maodouchat.call.MissedCallRecorder
import com.maodouchat.call.MissedCallTimeoutPolicy
import com.maodouchat.core.realtime.RealtimeDomainEvent
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.session.AppRuntime
import com.maodouchat.webrtc.CallReliabilityPolicy
import com.maodouchat.webrtc.CallState
import com.maodouchat.webrtc.CallType
import com.maodouchat.webrtc.GroupPeerConnectionState
import com.maodouchat.webrtc.WebRTCManager
import com.maodouchat.webrtc.WebRTCSignaling
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate

/**
 * 入站信令簇：WS/轮询双路接收、准入/幂等/排序门闩、按信令类型分发、
 * 群边 offer 缓冲与统一 flush——函数体从 `CallViewModel` 逐字搬入，
 * VM 状态经 lambda 注入（与同包其它 controller 同一装配模式），零行为改动。
 */
internal class CallSignalingIngressController(
    private val scope: CoroutineScope,
    private val app: Application,
    private val currentState: () -> CallUiState,
    private val updateState: ((CallUiState) -> CallUiState) -> Unit,
    private val token: () -> String,
    private val isCurrentCallSession: (Long, WebRTCManager?) -> Boolean,
    private val lastAcceptedCursor: (String) -> CallSignalingOrderPolicy.Cursor?,
    private val recordAcceptedCursor: (String, CallSignalingOrderPolicy.Cursor) -> Unit,
    private val rememberSignaling: (String, String, String, String, String) -> Boolean,
    private val activeCallId: () -> String,
    private val activeGroupId: () -> String,
    private val meshGroupMemberIds: () -> List<String>,
    private val groupMemberIds: () -> Set<String>,
    private val activeCallSession: () -> Long,
    private val webRTCManager: () -> WebRTCManager?,
    private val groupMesh: CallGroupMesh,
    private val signalingSender: CallSignalingSender,
    private val ringingTimeout: CallRingingTimeout,
    private val prepareIncomingCall: (String, String, String?, CallType, String, String, String, List<String>) -> Unit,
    private val text: (Int, Array<out Any>) -> String,
    private val failureReason: (Throwable) -> String,
    private val endCall: (Boolean, String?) -> Unit,
) {
    private var pollingJob: Job? = null
    private var webSocketJob: Job? = null
    // 8.56：群 mesh 中「成员已先接听、本端 manager 未建」时缓冲其 offer，接听后统一 flush，
    // 否则该边 offer 被静默丢弃导致成员连接永久缺失
    private val pendingGroupOffers = mutableMapOf<String, String>()

    fun observeSignaling() {
        // 独立管理两个 job：一个死掉不影响另一个；避免"WS  collector 死了但 polling 还活着导致永远不重连"
        if (webSocketJob?.isActive != true) {
            val signalOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            val dispatcher = AppRuntime.realtimeDispatcherOrNull(app)
            if (dispatcher != null) {
                webSocketJob = scope.launch {
                    dispatcher.allEvents.collect { event ->
                        if (
                            signalOwnerUserId.isBlank() ||
                            !BackgroundSessionGate.mayContinue(
                                expectedUserId = signalOwnerUserId,
                            )
                        ) {
                            return@collect
                        }
                        when (event) {
                            is RealtimeDomainEvent.CallSignaling -> {
                                handleSignalingMessage(
                                    event.type,
                                    event.payload,
                                    event.fromUserId,
                                    event.callId,
                                    event.groupId,
                                    event.groupMemberIds,
                                    event.groupInvite,
                                    event.epoch,
                                    event.sequence,
                                    event.idempotencyKey,
                                )
                            }
                            is RealtimeDomainEvent.RealtimeError -> {
                                if (
                                    event.code == "CALL_INVITE_RATE_LIMITED" &&
                                    currentState().callState == CallState.CALLING
                                ) {
                                    endCall(
                                        false,
                                        text(R.string.call_rate_limited, arrayOf(event.retryAfterSeconds ?: 60))
                                    )
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }

        if (pollingJob?.isActive != true) {
            val pollOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            pollingJob = scope.launch {
                while (currentState().callState != CallState.DISCONNECTED) {
                    delay(2000)
                    if (
                        pollOwnerUserId.isBlank() ||
                        !BackgroundSessionGate.mayContinue(
                            expectedUserId = pollOwnerUserId,
                        )
                    ) {
                        continue
                    }
                    val liveToken = token()
                    if (liveToken.isBlank()) continue
                    WebRTCSignaling.fetchPending(liveToken)
                        .onSuccess { messages ->
                            if (!BackgroundSessionGate.mayContinue(expectedUserId = pollOwnerUserId)) {
                                return@onSuccess
                            }
                            messages.forEach {
                                handleSignalingMessage(
                                    it.type,
                                    it.payload,
                                    it.fromUserId,
                                    it.callId,
                                    it.groupId,
                                    it.groupMemberIds,
                                    it.groupInvite,
                                    it.epoch,
                                    it.sequence,
                                    it.idempotencyKey,
                                )
                            }
                        }
                        .onFailure { error ->
                            if (!BackgroundSessionGate.mayContinue(expectedUserId = pollOwnerUserId)) {
                                return@onFailure
                            }
                            updateState { it.copy(errorMessage = text(R.string.call_fetch_signaling_failed, arrayOf(failureReason(error)))) }
                        }
                }
            }
        }
    }

    /** 挂断/销毁时停掉双路接收（job 置空，下次通话重新起）。 */
    fun stop() {
        pollingJob?.cancel()
        webSocketJob?.cancel()
        pollingJob = null
        webSocketJob = null
    }

    /**
     * 8.56：群 mesh——本端 manager 就绪后统一处理被缓冲的成员边 offer
     * （成员先接听、本端尚在 RINGING/CALLING 时，offer 曾被打消）。
     */
    fun flushPendingGroupOffers(manager: WebRTCManager, session: Long) {
        if (pendingGroupOffers.isEmpty()) return
        val entries = pendingGroupOffers.toList()
        pendingGroupOffers.clear()
        entries.forEach { (fromUserId, sdp) ->
            if (manager.hasGroupPeer(fromUserId)) return@forEach
            groupMesh.scheduleGroupPeerTimeout(fromUserId)
            manager.acceptGroupOffer(
                peerUserId = fromUserId,
                remoteOfferSdp = sdp,
                type = currentState().callType,
                onIceCandidate = { candidate ->
                    if (isCurrentCallSession(session, manager)) signalingSender.sendIceCandidate(fromUserId, candidate)
                },
                onAnswerCreated = { sdp2 ->
                    if (isCurrentCallSession(session, manager)) signalingSender.sendSdp(fromUserId, "answer", sdp2)
                }
            )
        }
    }

    /** 挂断时清群边 offer 缓冲——否则过期 offer 会串入下一通点对点通话（flush 误建 group peer）。 */
    fun clearPendingGroupOffers() {
        pendingGroupOffers.clear()
    }

    private fun handleSignalingMessage(
        type: String,
        payload: String,
        fromUserId: String = "",
        callId: String = "",
        groupId: String = "",
        groupMemberIds: List<String> = emptyList(),
        groupInvite: Boolean = false,
        epoch: Long = 0L,
        sequence: Long = 0L,
        idempotencyKey: String = "",
    ) {
        val currentState = currentState()
        val expectedContactId = currentState.contactId
        val normalizedType = CallReliabilityPolicy.normalizeSignalingType(type)
        val isGroup = currentState.isGroupCall
        val cursorKey = "$callId|$fromUserId"
        when (
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = isGroup,
                expectedContactId = expectedContactId,
                fromUserId = fromUserId,
                activeCallId = activeCallId(),
                incomingCallId = callId,
                activeGroupId = activeGroupId(),
                incomingGroupId = groupId,
                activeMembers = meshGroupMemberIds(),
                incomingMembers = groupMemberIds,
                signalType = type,
                incomingEpoch = epoch,
                incomingSequence = sequence,
                lastAcceptedCursor = lastAcceptedCursor(cursorKey),
            )
        ) {
            CallSignalingAdmissionPolicy.Decision.DROP -> return
            CallSignalingAdmissionPolicy.Decision.BUSY_REJECT -> {
                if (fromUserId.isNotBlank()) {
                    signalingSender.sendSignalWithFallback(
                        fromUserId,
                        "busy",
                        "",
                        text(R.string.call_notify_busy_failed, emptyArray()),
                        callId,
                        groupId,
                        groupMemberIds
                    )
                }
                return
            }
            CallSignalingAdmissionPolicy.Decision.ACCEPT -> Unit
        }

        if (!rememberSignaling(
                callId,
                fromUserId,
                normalizedType,
                payload,
                idempotencyKey,
            )
        ) {
            return
        }
        if (epoch != 0L || sequence != 0L) {
            recordAcceptedCursor(cursorKey, CallSignalingOrderPolicy.Cursor(epoch, sequence))
        }

        try {
            when (normalizedType) {
                "answer" -> {
                    val st = currentState()
                    if (st.callState == CallState.DISCONNECTED || st.callState == CallState.IDLE) return
                    ringingTimeout.cancel()
                    if (st.isGroupCall) {
                        groupMesh.cancelInviteTimeout(fromUserId)
                        webRTCManager()?.handleGroupAnswer(fromUserId, payload)
                    } else {
                        webRTCManager()?.handleAnswer(payload)
                    }
                    // BUG 3 fix: 不在此处设 CONNECTED，等 ICE onIceConnectionChange 回调设
                    updateState { it.copy(isInitializing = false) }
                }
                "ice-candidate" -> {
                    val parts = payload.split("|", limit = 3)
                    if (parts.size == 3) {
                        val sdpMLineIndex = parts[1].toIntOrNull()
                        if (sdpMLineIndex != null) {
                            val candidate = IceCandidate(parts[0], sdpMLineIndex, parts[2])
                            if (currentState.isGroupCall) {
                                webRTCManager()?.addGroupIceCandidate(fromUserId, candidate)
                            } else {
                                webRTCManager()?.addIceCandidate(candidate)
                            }
                        }
                    }
                }
                "hang-up" -> {
                    if (currentState.isGroupCall) {
                        if (
                            fromUserId == currentState.contactId &&
                            currentState.isIncoming &&
                            currentState.callState == CallState.RINGING
                        ) {
                            // Group initiator cancelled while this device was still ringing:
                            // same missed-call semantics as the 1:1 caller-gave-up path.
                            scope.launch {
                                try {
                                    MissedCallRecorder.recordRingTimeout(
                                        context = app,
                                        signalingCallId = activeCallId().ifBlank { callId },
                                        fromUserId = currentState.contactId.ifBlank { fromUserId },
                                        callerName = currentState.contactName,
                                        isVideo = currentState.callType == CallType.VIDEO,
                                        isGroup = currentState.isGroupCall,
                                    )
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    Log.w(
                                        "CallViewModel",
                                        "missed-call record on group initiator hang-up failed",
                                        error
                                    )
                                }
                            }
                            IncomingCallCoordinator.clear()
                            endCall(false, null)
                        } else {
                            webRTCManager()?.removeGroupPeer(fromUserId)
                            groupMesh.markGroupPeerTerminal(fromUserId, GroupPeerConnectionState.DISCONNECTED)
                        }
                    } else {
                        // Caller hung up while we were still ringing → missed call
                        // (NavGraph may also record via pending; stable callId REPLACE).
                        if (
                            MissedCallTimeoutPolicy.shouldRecordPeerCancelAsMissed(
                                isIncoming = currentState.isIncoming,
                                callStateWire = currentState.callState.name,
                            )
                        ) {
                            scope.launch {
                                try {
                                    MissedCallRecorder.recordRingTimeout(
                                        context = app,
                                        signalingCallId = activeCallId().ifBlank { callId },
                                        fromUserId = currentState.contactId.ifBlank { fromUserId },
                                        callerName = currentState.contactName,
                                        isVideo = currentState.callType == CallType.VIDEO,
                                    )
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    Log.w(
                                        "CallViewModel",
                                        "missed-call record on peer hang-up failed",
                                        error
                                    )
                                }
                            }
                            IncomingCallCoordinator.clear()
                        }
                        endCall(false, null)
                    }
                }
                "reject" -> {
                    if (currentState.isGroupCall) {
                        webRTCManager()?.removeGroupPeer(fromUserId)
                        groupMesh.markGroupPeerTerminal(fromUserId, GroupPeerConnectionState.REJECTED)
                    } else {
                        endCall(false, text(R.string.call_peer_rejected, emptyArray()))
                    }
                }
                "busy" -> {
                    if (currentState.isGroupCall) {
                        webRTCManager()?.removeGroupPeer(fromUserId)
                        groupMesh.markGroupPeerTerminal(fromUserId, GroupPeerConnectionState.BUSY)
                    } else {
                        endCall(false, text(R.string.call_peer_busy, emptyArray()))
                    }
                }
                "offer" -> {
                    val st = currentState()
                    if (st.callState == CallState.IDLE || st.callState == CallState.DISCONNECTED) {
                        val inferredType = CallType.detectFromSdp(payload)
                        prepareIncomingCall(fromUserId, fromUserId, null, inferredType, payload, callId, groupId, groupMemberIds)
                    } else if (
                        st.isGroupCall &&
                        groupId == activeGroupId() &&
                        fromUserId in groupMemberIds() &&
                        webRTCManager()?.hasGroupPeer(fromUserId) != true
                    ) {
                        groupMesh.scheduleGroupPeerTimeout(fromUserId)
                        val manager = webRTCManager()
                        if (manager == null) {
                            // 8.56：本端 manager 尚未创建（成员先接听）——缓冲该边 offer，
                            // 接听/建 manager 后统一 flush，避免边永久丢失
                            pendingGroupOffers[fromUserId] = payload
                        } else {
                            val session = activeCallSession()
                            manager.acceptGroupOffer(
                                peerUserId = fromUserId,
                                remoteOfferSdp = payload,
                                type = st.callType,
                                onIceCandidate = { candidate ->
                                    if (isCurrentCallSession(session, manager)) signalingSender.sendIceCandidate(fromUserId, candidate)
                                },
                                onAnswerCreated = { sdp ->
                                    if (isCurrentCallSession(session, manager)) signalingSender.sendSdp(fromUserId, "answer", sdp)
                                }
                            )
                        }
                    } else if (fromUserId.isNotBlank() && !st.isGroupCall) {
                        signalingSender.sendSignalWithFallback(
                            fromUserId,
                            "busy",
                            "",
                            text(R.string.call_notify_busy_failed, emptyArray()),
                            callId,
                            groupId,
                            groupMemberIds
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateState { it.copy(errorMessage = text(R.string.call_handle_signaling_failed, arrayOf(e.message ?: text(R.string.call_unknown_error, emptyArray())))) }
        }
    }
}
