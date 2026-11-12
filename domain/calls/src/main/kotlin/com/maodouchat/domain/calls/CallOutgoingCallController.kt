package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallState
import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.GroupCallParticipantUi
import com.maodouchat.core.model.NetworkQuality
import com.maodouchat.core.model.State
import com.maodouchat.core.model.WebRtcNativeLoadException
import com.maodouchat.core.session.GroupCallCapabilities
import com.maodouchat.domain.calls.CallForegroundServiceController
import com.maodouchat.domain.calls.CallRingingTimeout
import com.maodouchat.domain.calls.CallSignalingSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 呼出一族：从 app 侧 CallViewModel 迁入；VM 只改接线。
class CallOutgoingCallController(
    private val scope: CoroutineScope,
    private val currentState: () -> CallUiState,
    private val updateState: ((CallUiState) -> CallUiState) -> Unit,
    private val token: () -> String,
    private val text: (CallOutgoingCallMessage, Array<out Any>) -> String,
    private val callsEnabled: () -> Boolean,
    private val callTypeEnabled: (CallType) -> Boolean,
    private val resetForNewOutgoingCall: (peerId: String) -> Long,
    private val resetForNewGroupCall: (chatId: String, remoteMembers: List<String>, selfUserId: String) -> Long,
    private val groupMemberIds: () -> Set<String>,
    private val setGroupMemberIds: (Set<String>) -> Unit,
    private val currentOwnerUserId: () -> String,
    private val writeCallLog: (State) -> Unit,
    private val createWebRtc: suspend () -> CallWebRtcSession,
    private val configureWebRtc: (CallWebRtcSession, Long) -> Unit,
    private val bindMedia: (CallWebRtcSession) -> Unit,
    private val groupMesh: CallSignalingGroupMesh,
    private val sessionGateIsCurrent: (Long) -> Boolean,
    private val isCurrentCallSession: (Long, CallWebRtcSession?) -> Boolean,
    private val flushPendingGroupOffers: (CallWebRtcSession, Long) -> Unit,
    private val signalingSender: CallSignalingSender,
    private val ringingTimeout: CallRingingTimeout,
    private val foregroundService: CallForegroundServiceController,
    private val observeSignaling: () -> Unit,
    private val endCall: (notifyPeer: Boolean, errorMessage: String?, logMissed: Boolean) -> Unit,
) {
    fun startCall(contactId: String, contactName: String, contactAvatar: String?, callType: CallType) {
        if (currentState().callState != CallState.IDLE) return
        if (!callsEnabled()) {
            updateState {
                it.copy(
                    contactId = contactId,
                    contactName = contactName,
                    contactAvatar = contactAvatar,
                    callType = callType,
                    callState = CallState.DISCONNECTED,
                    isIncoming = false,
                    isGroupCall = false,
                    isInitializing = false,
                    errorMessage = text(CallOutgoingCallMessage.CALLS_DISABLED, emptyArray())
                )
            }
            return
        }
        val fineOk = callTypeEnabled(callType)
        if (!fineOk) {
            updateState {
                it.copy(
                    contactId = contactId,
                    contactName = contactName,
                    contactAvatar = contactAvatar,
                    callType = callType,
                    callState = CallState.DISCONNECTED,
                    isIncoming = false,
                    isGroupCall = false,
                    isInitializing = false,
                    errorMessage = text(
                        if (callType == CallType.VIDEO) CallOutgoingCallMessage.VIDEO_CALL_DISABLED else CallOutgoingCallMessage.VOICE_CALL_DISABLED,
                        emptyArray()
                    )
                )
            }
            return
        }
        if (token().isBlank()) {
            // Fail before CALLING/foreground service so the user is not left in a dead ringing UI.
            updateState {
                it.copy(
                    contactId = contactId,
                    contactName = contactName,
                    contactAvatar = contactAvatar,
                    callType = callType,
                    callState = CallState.DISCONNECTED,
                    isIncoming = false,
                    isGroupCall = false,
                    isInitializing = false,
                    errorMessage = text(CallOutgoingCallMessage.SESSION_EXPIRED, emptyArray())
                )
            }
            return
        }
        val session = resetForNewOutgoingCall(contactId)
        updateState {
            it.copy(
                contactId = contactId,
                contactName = contactName,
                contactAvatar = contactAvatar,
                callType = callType,
                callState = CallState.CALLING,
                isIncoming = false,
                isGroupCall = false,
                isInitializing = true,
                networkReconnecting = false,
                networkStats = NetworkQuality.UNKNOWN,
                errorMessage = null
            )
        }
        foregroundService.start()
        // 8.46 修复：振铃超时改到 offer 发出后启动（此前在 createWebRtcManager 之前——
        // 慢网下载 ~10MB 原生库 + 拉取 TURN 可能 >30s，超时在对端还没开始振铃时就触发挂断）
        writeCallLog(State.MISSED)

        scope.launch {
            try {
                val webRtc = createWebRtc()
                if (!sessionGateIsCurrent(session) || currentState().callState != CallState.CALLING || currentState().contactId != contactId) {
                    // 8.56：门禁失效时释放已构造的 webRtc，避免残留（audio 监听等）
                    runCatching { webRtc.release() }
                    return@launch
                }
                bindMedia(webRtc)
                configureWebRtc(webRtc, session)
                webRtc.initialize()
                // 8.56：建 webRtc 后 flush 群成员边缓冲（发起者在初始化期间被先接听成员 offer）
                flushPendingGroupOffers(webRtc, session)
                webRtc.startCall(
                    type = callType,
                    onIceCandidate = { sdpMid, sdpMLineIndex, sdp ->
                        if (isCurrentCallSession(session, webRtc)) signalingSender.sendIceCandidate(contactId, sdpMid, sdpMLineIndex, sdp)
                    },
                    onOfferCreated = { description ->
                        if (!isCurrentCallSession(session, webRtc)) return@startCall
                        updateState { it.copy(isInitializing = false) }
                        signalingSender.sendSdp(contactId, "offer", description)
                        ringingTimeout.start(contactId)
                    }
                )
                observeSignaling()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: WebRtcNativeLoadException) {
                endCall(false, text(CallOutgoingCallMessage.WEBRTC_DOWNLOAD_FAILED, arrayOf(e.message.orEmpty())), true)
            } catch (e: Exception) {
                endCall(false, text(CallOutgoingCallMessage.INITIALIZATION_FAILED, arrayOf(e.message ?: text(CallOutgoingCallMessage.UNKNOWN_ERROR, emptyArray()))), true)
            }
        }
    }

    fun startGroupCall(chatId: String, memberIds: List<String>, type: CallType) {
        if (currentState().callState != CallState.IDLE) return
        val selfUserId = currentOwnerUserId()
        val remoteMembers = memberIds.filter { it.isNotBlank() && it != selfUserId }.distinct()
        if (remoteMembers.isEmpty()) return
        if (token().isBlank()) {
            updateState {
                it.copy(
                    contactId = chatId,
                    contactName = text(CallOutgoingCallMessage.GROUP_CALL, emptyArray()),
                    callType = type,
                    callState = CallState.DISCONNECTED,
                    isGroupCall = true,
                    isInitializing = false,
                    errorMessage = text(CallOutgoingCallMessage.SESSION_EXPIRED, emptyArray())
                )
            }
            return
        }
        if (selfUserId.isBlank() ||
            !GroupCallCapabilities.canStartMesh(remoteMembers.size + 1)
        ) {
            updateState {
                it.copy(
                    contactId = chatId,
                    contactName = text(CallOutgoingCallMessage.GROUP_CALL, emptyArray()),
                    callType = type,
                    callState = CallState.DISCONNECTED,
                    errorMessage = text(CallOutgoingCallMessage.GROUP_MESH_LIMIT, arrayOf(GroupCallCapabilities.MAX_MESH_MEMBERS))
                )
            }
            return
        }
        val session = resetForNewGroupCall(chatId, remoteMembers, selfUserId)
        updateState {
            it.copy(
                contactId = chatId,
                contactName = text(CallOutgoingCallMessage.GROUP_NAME, arrayOf(remoteMembers.size)),
                callType = type,
                callState = CallState.CALLING,
                isIncoming = false,
                isGroupCall = true,
                isInitializing = true,
                groupParticipants = remoteMembers.map { GroupCallParticipantUi(it) },
                errorMessage = null
            )
        }
        setGroupMemberIds(remoteMembers.toSet())
        groupMesh.loadGroupParticipantProfiles()
        // 8.46 修复：群呼振铃超时改到首条 offer 发出后启动（与 1:1 呼出一致，
        // 避免慢网原生库下载/ICE 拉取超过 30s 时在对端还没开始振铃就挂断）
        foregroundService.start()
        scope.launch {
            try {
                val webRtc = createWebRtc()
                val state = currentState()
                if (!sessionGateIsCurrent(session) || !state.isGroupCall || state.callState != CallState.CALLING || state.contactId != chatId) {
                    // 8.56：与 startCall/answerCall 一致——门禁失效时释放已构造的 webRtc
                    runCatching { webRtc.release() }
                    return@launch
                }
                bindMedia(webRtc)
                configureWebRtc(webRtc, session)
                webRtc.initialize()
                var firstOfferSent = false
                groupMemberIds().forEach { memberId ->
                    groupMesh.scheduleGroupPeerTimeout(memberId)
                    webRtc.startGroupCallToPeer(
                        peerUserId = memberId,
                        type = type,
                        onIceCandidate = { sdpMid, sdpMLineIndex, sdp ->
                            if (isCurrentCallSession(session, webRtc)) signalingSender.sendIceCandidate(memberId, sdpMid, sdpMLineIndex, sdp)
                        },
                        onOfferCreated = { description ->
                            if (isCurrentCallSession(session, webRtc)) {
                                signalingSender.sendSdp(memberId, "offer", description, groupInvite = true)
                                if (!firstOfferSent) {
                                    firstOfferSent = true
                                    ringingTimeout.start(chatId)
                                }
                            }
                        }
                    )
                }
                observeSignaling()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: WebRtcNativeLoadException) {
                endCall(false, text(CallOutgoingCallMessage.WEBRTC_DOWNLOAD_FAILED, arrayOf(e.message.orEmpty())), true)
            } catch (e: Exception) {
                endCall(false, text(CallOutgoingCallMessage.GROUP_START_FAILED, arrayOf(e.message ?: text(CallOutgoingCallMessage.UNKNOWN_ERROR, emptyArray()))), true)
            }
        }
    }
}
