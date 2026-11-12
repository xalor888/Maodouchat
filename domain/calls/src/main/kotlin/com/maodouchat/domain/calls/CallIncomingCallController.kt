package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallState
import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.GroupCallParticipantUi
import com.maodouchat.core.model.WebRtcNativeLoadException
import com.maodouchat.core.session.CallSessionMachine
import com.maodouchat.domain.calls.CallForegroundServiceController
import com.maodouchat.domain.calls.CallRingingTimeout
import com.maodouchat.domain.calls.CallSignalingSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 呼入一族：从 app 侧 CallViewModel 迁入；VM 只改接线。
class CallIncomingCallController(
    private val scope: CoroutineScope,
    private val currentState: () -> CallUiState,
    private val updateState: ((CallUiState) -> CallUiState) -> Unit,
    private val token: () -> String,
    private val text: (CallIncomingCallMessage, Array<out Any>) -> String,
    private val cancelIncomingCall: (String) -> Unit,
    private val activeCallId: () -> String,
    private val activeCallSession: () -> Long,
    private val pendingOfferSdp: () -> String?,
    private val clearPendingOfferSdp: () -> Unit,
    private val resetForNewIncomingCall: (contactId: String, offerSdp: String, callId: String, groupId: String, groupMemberIds: List<String>) -> Pair<Boolean, List<GroupCallParticipantUi>>,
    private val markIncomingSessionOwner: () -> Unit,
    private val signalingSender: CallSignalingSender,
    private val groupMesh: CallSignalingGroupMesh,
    private val createWebRtc: suspend () -> CallWebRtcSession,
    private val configureWebRtc: (CallWebRtcSession, Long) -> Unit,
    private val bindMedia: (CallWebRtcSession) -> Unit,
    private val sessionGateIsCurrent: (Long) -> Boolean,
    private val isCurrentCallSession: (Long, CallWebRtcSession?) -> Boolean,
    private val flushPendingGroupOffers: (CallWebRtcSession, Long) -> Unit,
    private val callSessionMachine: CallSessionMachine,
    private val activeDomainSession: () -> Long,
    private val ringingTimeout: CallRingingTimeout,
    private val foregroundService: CallForegroundServiceController,
    private val observeSignaling: () -> Unit,
    private val endCall: (notifyPeer: Boolean, errorMessage: String?, logMissed: Boolean) -> Unit,
) {
    // 只展示响铃，不初始化 WebRTC，等用户授权接听。
    fun prepareIncomingCall(
        contactId: String,
        contactName: String,
        contactAvatar: String?,
        callType: CallType,
        offerSdp: String,
        callId: String = "",
        groupId: String = "",
        groupMemberIds: List<String> = emptyList()
    ) {
        val current = currentState()
        if (current.callState != CallState.IDLE && current.callState != CallState.DISCONNECTED) {
            if (current.contactId != contactId) {
                signalingSender.sendSignalWithFallback(
                    contactId,
                    "busy",
                    "",
                    text(CallIncomingCallMessage.NOTIFY_BUSY_FAILED, emptyArray()),
                    callId,
                    groupId,
                    groupMemberIds
                )
            }
            return
        }
        val (isGroup, participants) = resetForNewIncomingCall(contactId, offerSdp, callId, groupId, groupMemberIds)
        // In-app ring UI owns the call — drop FCM/full-screen incoming tray so shade
        // does not keep a second "encrypted call" while CallScreen is already open.
        if (callId.isNotBlank()) {
            cancelIncomingCall(callId)
        }
        updateState {
            it.copy(
                contactId = contactId,
                contactName = if (isGroup) text(CallIncomingCallMessage.GROUP_NAME, arrayOf(participants.size)) else contactName,
                contactAvatar = contactAvatar,
                callType = callType,
                callState = CallState.RINGING,
                isIncoming = true,
                isGroupCall = isGroup,
                isInitializing = false,
                groupParticipants = participants,
                errorMessage = null
            )
        }
        markIncomingSessionOwner()
        if (isGroup) groupMesh.loadGroupParticipantProfiles()
        ringingTimeout.start(contactId)
        observeSignaling()
    }

    fun answerCall(contactId: String? = null, contactName: String? = null, contactAvatar: String? = null, callType: CallType? = null, offerSdp: String? = null) {
        val state = currentState()
        val targetContactId = contactId ?: state.contactId
        val targetContactName = contactName ?: state.contactName
        val targetCallType = callType ?: state.callType
        val targetOfferSdp = offerSdp ?: pendingOfferSdp()
        if (targetContactId.isBlank() || targetOfferSdp.isNullOrBlank()) {
            updateState { it.copy(errorMessage = text(CallIncomingCallMessage.INCOMING_INCOMPLETE, emptyArray())) }
            return
        }
        if (state.callState != CallState.RINGING || !state.isIncoming || state.isInitializing) return
        if (token().isBlank()) {
            endCall(false, text(CallIncomingCallMessage.SESSION_EXPIRED, emptyArray()), true)
            return
        }
        // 8.39：用户已接听即取消 30s 振铃超时——否则 WebRTC 原生库首次联网下载（慢网可超 30s）
        // 期间超时触发「无应答」挂断，用户明明已接听却被直接挂断
        ringingTimeout.cancel()
        callSessionMachine.markConnecting(activeDomainSession())
        // Accepting from in-app UI must clear any leftover FCM incoming tray.
        if (activeCallId().isNotBlank()) {
            cancelIncomingCall(activeCallId())
        }
        updateState {
            it.copy(
                contactId = targetContactId,
                contactName = targetContactName,
                contactAvatar = contactAvatar ?: state.contactAvatar,
                callType = targetCallType,
                callState = CallState.RINGING,
                isIncoming = true,
                isInitializing = true,
                errorMessage = null
            )
        }
        foregroundService.start()
        val session = activeCallSession()

        scope.launch {
            try {
                val webRtc = createWebRtc()
                val latest = currentState()
                if (!sessionGateIsCurrent(session) || latest.callState != CallState.RINGING || !latest.isIncoming || latest.contactId != targetContactId) {
                    // 8.56：门禁失效时释放已构造的 webRtc，避免残留（audio 监听等）
                    runCatching { webRtc.release() }
                    return@launch
                }
                bindMedia(webRtc)
                configureWebRtc(webRtc, session)
                webRtc.initialize()
                if (latest.isGroupCall) {
                    groupMesh.scheduleGroupPeerTimeout(targetContactId)
                    // 8.56：接听后统一 flush 被缓冲的群成员边 offer（修复成员先接听导致边永久丢失）
                    flushPendingGroupOffers(webRtc, session)
                    webRtc.acceptGroupOffer(
                        peerUserId = targetContactId,
                        remoteOfferSdp = targetOfferSdp,
                        type = targetCallType,
                        onIceCandidate = { sdpMid, sdpMLineIndex, sdp ->
                            if (isCurrentCallSession(session, webRtc)) signalingSender.sendIceCandidate(targetContactId, sdpMid, sdpMLineIndex, sdp)
                        },
                        onAnswerCreated = { description ->
                            if (!isCurrentCallSession(session, webRtc)) return@acceptGroupOffer
                            ringingTimeout.cancel()
                            clearPendingOfferSdp()
                            updateState { it.copy(isInitializing = false) }
                            signalingSender.sendSdp(targetContactId, "answer", description)
                        }
                    )
                    groupMesh.startDeterministicMeshEdges(webRtc, targetContactId, session)
                    observeSignaling()
                    return@launch
                }
                webRtc.answerCall(
                    remoteOfferSdp = targetOfferSdp,
                    type = targetCallType,
                    onIceCandidate = { sdpMid, sdpMLineIndex, sdp ->
                        if (isCurrentCallSession(session, webRtc)) signalingSender.sendIceCandidate(targetContactId, sdpMid, sdpMLineIndex, sdp)
                    },
                    onAnswerCreated = { description ->
                        if (!isCurrentCallSession(session, webRtc)) return@answerCall
                        ringingTimeout.cancel()
                        clearPendingOfferSdp()
                        updateState { it.copy(isInitializing = false) }
                        signalingSender.sendSdp(targetContactId, "answer", description)
                    }
                )
                observeSignaling()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: WebRtcNativeLoadException) {
                endCall(false, text(CallIncomingCallMessage.WEBRTC_DOWNLOAD_FAILED, arrayOf(e.message.orEmpty())), true)
            } catch (e: Exception) {
                endCall(false, text(CallIncomingCallMessage.ANSWER_FAILED, arrayOf(e.message ?: text(CallIncomingCallMessage.UNKNOWN_ERROR, emptyArray()))), true)
            }
        }
    }

    fun rejectIncomingCall() {
        val contactId = currentState().contactId
        val callId = activeCallId()
        if (contactId.isNotBlank()) {
            signalingSender.sendSignalWithFallback(contactId, "reject", "", text(CallIncomingCallMessage.NOTIFY_REJECT_FAILED, emptyArray()))
        }
        // User declined — never leave FCM tray ringing after reject.
        if (callId.isNotBlank()) {
            cancelIncomingCall(callId)
        }
        // 8.53：主动拒接非「未接」——不写 MISSED 通话记录（对端忙/拒接由呼出侧记未接通）
        endCall(false, null, false)
    }
}
