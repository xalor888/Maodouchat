package com.maodouchat.ui.screen.call

import android.app.Application
import com.maodouchat.R
import com.maodouchat.call.CallMediaBridge
import com.maodouchat.call.CallSessionMachine
import com.maodouchat.notification.CallNotificationService
import com.maodouchat.webrtc.CallState
import com.maodouchat.webrtc.CallType
import com.maodouchat.webrtc.WebRTCManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 呼入一族：从 CallViewModel 纯搬移；VM 留同签名委托。
internal class CallIncomingCallController(
    private val scope: CoroutineScope,
    private val app: Application,
    private val currentState: () -> CallUiState,
    private val updateState: ((CallUiState) -> CallUiState) -> Unit,
    private val token: () -> String,
    private val text: (Int, Array<out Any>) -> String,
    private val activeCallId: () -> String,
    private val activeCallSession: () -> Long,
    private val pendingOfferSdp: () -> String?,
    private val clearPendingOfferSdp: () -> Unit,
    private val resetForNewIncomingCall: (contactId: String, offerSdp: String, callId: String, groupId: String, groupMemberIds: List<String>) -> Pair<Boolean, List<GroupCallParticipantUi>>,
    private val markIncomingSessionOwner: () -> Unit,
    private val signalingSender: CallSignalingSender,
    private val groupMesh: CallGroupMesh,
    private val webRtcSetup: CallWebRtcSetupController,
    private val mediaBridge: CallMediaBridge,
    private val sessionGateIsCurrent: (Long) -> Boolean,
    private val isCurrentCallSession: (Long, WebRTCManager?) -> Boolean,
    private val flushPendingGroupOffers: (WebRTCManager, Long) -> Unit,
    private val callSessionMachine: CallSessionMachine,
    private val activeDomainSession: () -> Long,
    private val ringingTimeout: CallRingingTimeout,
    private val foregroundService: CallForegroundServiceController,
    private val observeSignaling: () -> Unit,
    private val endCall: (notifyPeer: Boolean, errorMessage: String?, logMissed: Boolean) -> Unit,
) {
    /**
     * 准备来电界面：只展示响铃，不初始化 WebRTC，等待用户授权并接听。
     */
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
                    text(R.string.call_notify_busy_failed, emptyArray()),
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
            CallNotificationService.cancelIncomingCall(app, callId)
        }
        updateState {
            it.copy(
                contactId = contactId,
                contactName = if (isGroup) text(R.string.call_group_name, arrayOf(participants.size)) else contactName,
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

    /**
     * 接听来电
     */
    fun answerCall(contactId: String? = null, contactName: String? = null, contactAvatar: String? = null, callType: CallType? = null, offerSdp: String? = null) {
        val state = currentState()
        val targetContactId = contactId ?: state.contactId
        val targetContactName = contactName ?: state.contactName
        val targetCallType = callType ?: state.callType
        val targetOfferSdp = offerSdp ?: pendingOfferSdp()
        if (targetContactId.isBlank() || targetOfferSdp.isNullOrBlank()) {
            updateState { it.copy(errorMessage = text(R.string.call_incoming_incomplete, emptyArray())) }
            return
        }
        if (state.callState != CallState.RINGING || !state.isIncoming || state.isInitializing) return
        if (token().isBlank()) {
            endCall(notifyPeer = false, errorMessage = text(R.string.call_session_expired, emptyArray()), logMissed = true)
            return
        }
        // 8.39：用户已接听即取消 30s 振铃超时——否则 WebRTC 原生库首次联网下载（慢网可超 30s）
        // 期间超时触发「无应答」挂断，用户明明已接听却被直接挂断
        ringingTimeout.cancel()
        callSessionMachine.markConnecting(activeDomainSession())
        // Accepting from in-app UI must clear any leftover FCM incoming tray.
        if (activeCallId().isNotBlank()) {
            CallNotificationService.cancelIncomingCall(app, activeCallId())
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
                val manager = webRtcSetup.createWebRtcManager()
                val latest = currentState()
                if (!sessionGateIsCurrent(session) || latest.callState != CallState.RINGING || !latest.isIncoming || latest.contactId != targetContactId) {
                    // 8.56：门禁失效时释放已构造的 manager，避免残留（audio 监听等）
                    runCatching { manager.release() }
                    return@launch
                }
                mediaBridge.bind(manager)
                webRtcSetup.configureReliabilityCallbacks(manager, session)
                manager.initialize()
                if (latest.isGroupCall) {
                    groupMesh.scheduleGroupPeerTimeout(targetContactId)
                    // 8.56：接听后统一 flush 被缓冲的群成员边 offer（修复成员先接听导致边永久丢失）
                    flushPendingGroupOffers(manager, session)
                    manager.acceptGroupOffer(
                        peerUserId = targetContactId,
                        remoteOfferSdp = targetOfferSdp,
                        type = targetCallType,
                        onIceCandidate = { candidate ->
                            if (isCurrentCallSession(session, manager)) signalingSender.sendIceCandidate(targetContactId, candidate)
                        },
                        onAnswerCreated = { sdp ->
                            if (!isCurrentCallSession(session, manager)) return@acceptGroupOffer
                            ringingTimeout.cancel()
                            clearPendingOfferSdp()
                            updateState { it.copy(isInitializing = false) }
                            signalingSender.sendSdp(targetContactId, "answer", sdp)
                        }
                    )
                    groupMesh.startDeterministicMeshEdges(manager, targetContactId, session)
                    observeSignaling()
                    return@launch
                }
                manager.answerCall(
                    remoteOfferSdp = targetOfferSdp,
                    type = targetCallType,
                    onIceCandidate = { candidate ->
                        if (isCurrentCallSession(session, manager)) signalingSender.sendIceCandidate(targetContactId, candidate)
                    },
                    onAnswerCreated = { sdp ->
                        if (!isCurrentCallSession(session, manager)) return@answerCall
                        ringingTimeout.cancel()
                        clearPendingOfferSdp()
                        updateState { it.copy(isInitializing = false) }
                        signalingSender.sendSdp(targetContactId, "answer", sdp)
                    }
                )
                observeSignaling()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: WebRtcNativeLoadException) {
                endCall(notifyPeer = false, errorMessage = text(R.string.call_webrtc_download_failed, arrayOf(e.message.orEmpty())), logMissed = true)
            } catch (e: Exception) {
                endCall(notifyPeer = false, errorMessage = text(R.string.call_answer_failed, arrayOf(e.message ?: text(R.string.call_unknown_error, emptyArray()))), logMissed = true)
            }
        }
    }

    fun rejectIncomingCall() {
        val contactId = currentState().contactId
        val callId = activeCallId()
        if (contactId.isNotBlank()) {
            signalingSender.sendSignalWithFallback(contactId, "reject", "", text(R.string.call_notify_reject_failed, emptyArray()))
        }
        // User declined — never leave FCM tray ringing after reject.
        if (callId.isNotBlank()) {
            CallNotificationService.cancelIncomingCall(app, callId)
        }
        // 8.53：主动拒接非「未接」——不写 MISSED 通话记录（对端忙/拒接由呼出侧记未接通）
        endCall(notifyPeer = false, errorMessage = null, logMissed = false)
    }
}
