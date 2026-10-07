package com.maodouchat.ui.screen.call

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.call.CallActionBus
import com.maodouchat.session.AppRuntime
import com.maodouchat.call.CallMediaBridge
import com.maodouchat.call.CallSignalingIdempotencyStore
import com.maodouchat.call.CallSignalingOrderPolicy
import com.maodouchat.call.CallSignalingOutboundCursor
import com.maodouchat.call.CallSystemIntegration
import com.maodouchat.call.GroupCallCapabilities
import com.maodouchat.call.WebRtcNativeLibraryLoader
import com.maodouchat.webrtc.CallState
import com.maodouchat.webrtc.CallType
import com.maodouchat.webrtc.CallReliabilityPolicy
import com.maodouchat.webrtc.CallSessionGate
import com.maodouchat.webrtc.CallAudioRoute
import com.maodouchat.webrtc.WebRTCManager
import com.maodouchat.webrtc.WebRTCSignaling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class CallViewModel(application: Application) : AndroidViewModel(application) {

    private val mediaBridge = CallMediaBridge()
    private val webRTCManager: WebRTCManager? get() = mediaBridge.manager
    private val token: String get() = com.maodouchat.session.CurrentSession.snapshot().token ?: ""

    private val app: Application get() = getApplication()

    private fun text(id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private fun failureReason(error: Throwable): String =
        if (error is WebRTCSignaling.SignalingException && error.code == "CALL_INVITE_RATE_LIMITED") {
            text(R.string.call_rate_limited, error.retryAfterSeconds ?: 60)
        } else if (error is WebRTCSignaling.SignalingException) text(R.string.call_network_error)
        else error.message ?: text(R.string.call_network_error)

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private var pendingOfferSdp: String? = null
    private var activeCallId: String = ""
    private val callSessionGate = CallSessionGate()
    private val callSessionMachine = com.maodouchat.call.CallSessionMachine()
    private val callSystemIntegration = CallSystemIntegration(application)
    private val foregroundService = CallForegroundServiceController(
        systemIntegration = callSystemIntegration,
        currentState = { _uiState.value },
        activeCallId = { activeCallId },
        text = { id, args -> text(id, *args) },
    )
    private var activeCallSession: Long = 0L
    private var activeDomainSession: Long = 0L
    private var activeGroupId: String = ""
    private var meshGroupMemberIds: List<String> = emptyList()
    // 8.56：volatile——onIceConnectionChange 等在 WebRTC signaling 线程回调，与主线程 endCall 并发
    @Volatile
    private var endingCall = false
    private var activeGroupMemberIds: Set<String> = emptySet()
    private val signalingIdempotency = CallSignalingIdempotencyStore()
    private val outboundSignalingCursor = CallSignalingOutboundCursor()
    private val hangUpSender = CallHangUpSender(outboundSignalingCursor)
    /** Per sender last accepted (epoch, sequence) for the active call. */
    private val inboundSignalingCursors = mutableMapOf<String, CallSignalingOrderPolicy.Cursor>()
    // 8.55：通话开始时的账号快照——writeCallLog 用其作 expectedUserId 守卫，
    // 通话中异地登出换号后旧通话不写进新账号 key
    private var callLogOwnerUserId: String = ""

    private companion object {
        const val RINGING_TIMEOUT_MS = 30_000L
        /** TURN 短期凭据 TTL 1h：每 30 分钟刷新一次，留足余量（8.35）。 */
    }

    private val timersController by lazy {
        CallSessionTimersController(
            scope = viewModelScope,
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            webRTCManager = { webRTCManager },
            activeCallSession = { activeCallSession },
            callSessionGate = callSessionGate,
            token = { token },
            onLogAnswered = { writeCallLog(com.maodouchat.call.CallLogStore.State.ANSWERED) },
        )
    }

    private val signalingSender by lazy {
        CallSignalingSender(
            scope = viewModelScope,
            token = { token },
            activeCallId = { activeCallId },
            activeGroupId = { activeGroupId },
            meshGroupMemberIds = { meshGroupMemberIds },
            activeCallSession = { activeCallSession },
            callSessionGate = callSessionGate,
            outboundSignalingCursor = outboundSignalingCursor,
            updateState = { transform -> _uiState.update(transform) },
            text = { id, args -> text(id, *args) },
            failureReason = { error -> failureReason(error) },
            onEndCall = { errorMessage -> endCall(notifyPeer = false, errorMessage = errorMessage) },
        )
    }

    private val groupMesh by lazy {
        CallGroupMesh(
            scope = viewModelScope,
            token = { token },
            endingCall = { endingCall },
            activeCallSession = { activeCallSession },
            activeDomainSession = { activeDomainSession },
            groupMemberIds = { activeGroupMemberIds },
            removeGroupMemberId = { userId -> activeGroupMemberIds = activeGroupMemberIds - userId },
            cancelRingingTimeout = { ringingTimeout.cancel() },
            ringingTimeoutMs = RINGING_TIMEOUT_MS,
            updateState = { transform -> _uiState.update(transform) },
            currentState = { _uiState.value },
            callSessionMachine = callSessionMachine,
            callSessionGate = callSessionGate,
            timersController = timersController,
            webRTCManager = { webRTCManager },
            isCurrentCallSession = { session, manager -> isCurrentCallSession(session, manager) },
            signalingSender = signalingSender,
            text = { id, args -> text(id, *args) },
            onNoActivePeers = { message -> endCall(notifyPeer = false, errorMessage = message) },
        )
    }

    private val iceRecovery by lazy {
        CallIceRecovery(
            scope = viewModelScope,
            endingCall = { endingCall },
            callSessionMachine = callSessionMachine,
            activeDomainSession = { activeDomainSession },
            timersController = timersController,
            activeCallSession = { activeCallSession },
            callSessionGate = callSessionGate,
            updateState = { transform -> _uiState.update(transform) },
            currentState = { _uiState.value },
            onIceGiveUp = { message -> endCall(notifyPeer = false, errorMessage = message) },
            text = { id, args -> text(id, *args) },
            webRTCManager = { webRTCManager },
        )
    }

    private val ringingTimeout by lazy {
        CallRingingTimeout(
            scope = viewModelScope,
            context = app,
            sessionGate = callSessionGate,
            activeCallSession = { activeCallSession },
            currentState = { _uiState.value },
            activeCallId = { activeCallId },
            timeoutMs = RINGING_TIMEOUT_MS,
            text = { id, args -> text(id, *args) },
            onNoAnswer = { message -> endCall(notifyPeer = true, errorMessage = message) },
        )
    }

    private val webRtcSetup by lazy {
        CallWebRtcSetupController(
            app = app,
            token = { token },
            updateState = { transform -> _uiState.update(transform) },
            currentState = { _uiState.value },
            endingCall = { endingCall },
            callSessionGate = callSessionGate,
            webRTCManager = { webRTCManager },
            iceRecovery = iceRecovery,
            groupMesh = groupMesh,
            endCall = ::endCall,
            text = { id, args -> text(id, *args) },
        )
    }

    private val outgoingCallController: CallOutgoingCallController by lazy {
        CallOutgoingCallController(
            scope = viewModelScope,
            app = app,
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            token = { token },
            text = { id, args -> text(id, *args) },
            resetForNewOutgoingCall = { peerId ->
                endingCall = false
                val session = beginCallSession(peerId)
                activeCallId = newCallId()
                activeGroupId = ""
                meshGroupMemberIds = emptyList()
                // 8.55：呼出时快照账号，作为通话记录写入的 expectedUserId 守卫
                callLogOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
                session
            },
            resetForNewGroupCall = { chatId, remoteMembers, selfUserId ->
                endingCall = false
                val session = beginCallSession(chatId)
                activeCallId = newCallId()
                activeGroupId = chatId
                meshGroupMemberIds = (remoteMembers + selfUserId).sorted()
                session
            },
            groupMemberIds = { activeGroupMemberIds },
            setGroupMemberIds = { ids -> activeGroupMemberIds = ids },
            currentOwnerUserId = { com.maodouchat.session.CurrentSession.ownerUserId() },
            writeCallLog = { state -> writeCallLog(state) },
            webRtcSetup = webRtcSetup,
            mediaBridge = mediaBridge,
            groupMesh = groupMesh,
            sessionGateIsCurrent = { session -> callSessionGate.isCurrent(session) },
            isCurrentCallSession = { session, manager -> isCurrentCallSession(session, manager) },
            flushPendingGroupOffers = { manager, session -> signalingIngress.flushPendingGroupOffers(manager, session) },
            signalingSender = signalingSender,
            ringingTimeout = ringingTimeout,
            foregroundService = foregroundService,
            observeSignaling = { signalingIngress.observeSignaling() },
            endCall = ::endCall,
        )
    }

    private val incomingCallController: CallIncomingCallController by lazy {
        CallIncomingCallController(
            scope = viewModelScope,
            app = app,
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            token = { token },
            text = { id, args -> text(id, *args) },
            activeCallId = { activeCallId },
            activeCallSession = { activeCallSession },
            pendingOfferSdp = { pendingOfferSdp },
            clearPendingOfferSdp = { pendingOfferSdp = null },
            resetForNewIncomingCall = { contactId, offerSdp, callId, groupId, groupMemberIds ->
                pendingOfferSdp = offerSdp
                endingCall = false
                beginCallSession(contactId, incoming = true)
                activeCallId = callId
                val selfUserId = com.maodouchat.session.CurrentSession.ownerUserId()
                val normalizedMembers = groupMemberIds.filter(String::isNotBlank).distinct()
                val isGroup = groupId.isNotBlank() &&
                    GroupCallCapabilities.canStartMesh(normalizedMembers.size) &&
                    selfUserId in normalizedMembers
                activeGroupId = if (isGroup) groupId else ""
                meshGroupMemberIds = if (isGroup) normalizedMembers.sorted() else emptyList()
                activeGroupMemberIds = if (isGroup) normalizedMembers.filter { it != selfUserId }.toSet() else emptySet()
                isGroup to activeGroupMemberIds.map { GroupCallParticipantUi(it) }
            },
            markIncomingSessionOwner = {
                // 8.55：呼入时快照账号，作为通话记录写入的 expectedUserId 守卫
                callLogOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            },
            signalingSender = signalingSender,
            groupMesh = groupMesh,
            webRtcSetup = webRtcSetup,
            mediaBridge = mediaBridge,
            sessionGateIsCurrent = { session -> callSessionGate.isCurrent(session) },
            isCurrentCallSession = { session, manager -> isCurrentCallSession(session, manager) },
            flushPendingGroupOffers = { manager, session -> signalingIngress.flushPendingGroupOffers(manager, session) },
            callSessionMachine = callSessionMachine,
            activeDomainSession = { activeDomainSession },
            ringingTimeout = ringingTimeout,
            foregroundService = foregroundService,
            observeSignaling = { signalingIngress.observeSignaling() },
            endCall = ::endCall,
        )
    }

    private val signalingIngress: CallSignalingIngressController by lazy {
        CallSignalingIngressController(
            scope = viewModelScope,
            app = app,
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            token = { token },
            isCurrentCallSession = { session, manager -> isCurrentCallSession(session, manager) },
            lastAcceptedCursor = { key -> inboundSignalingCursors[key] },
            recordAcceptedCursor = { key, cursor -> inboundSignalingCursors[key] = cursor },
            rememberSignaling = { callId, fromUserId, type, payload, idempotencyKey ->
                signalingIdempotency.remember(
                    callId = callId,
                    fromUserId = fromUserId,
                    type = type,
                    payload = payload,
                    idempotencyKey = idempotencyKey,
                )
            },
            activeCallId = { activeCallId },
            activeGroupId = { activeGroupId },
            meshGroupMemberIds = { meshGroupMemberIds },
            groupMemberIds = { activeGroupMemberIds },
            activeCallSession = { activeCallSession },
            webRTCManager = { webRTCManager },
            groupMesh = groupMesh,
            signalingSender = signalingSender,
            ringingTimeout = ringingTimeout,
            prepareIncomingCall = { contactId, contactName, contactAvatar, callType, offerSdp, callId, groupId, groupMemberIds ->
                incomingCallController.prepareIncomingCall(contactId, contactName, contactAvatar, callType, offerSdp, callId, groupId, groupMemberIds)
            },
            text = { id, args -> text(id, *args) },
            failureReason = { error -> failureReason(error) },
            endCall = { notifyPeer, errorMessage -> endCall(notifyPeer, errorMessage) },
        )
    }

    private val teardownController: CallTeardownController by lazy {
        CallTeardownController(
            app = app,
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            token = { token },
            endingCall = { endingCall },
            setEndingCall = { endingCall = it },
            activeCallId = { activeCallId },
            setActiveCallId = { activeCallId = it },
            activeGroupId = { activeGroupId },
            setActiveGroupId = { activeGroupId = it },
            meshGroupMemberIds = { meshGroupMemberIds },
            setMeshGroupMemberIds = { meshGroupMemberIds = it },
            groupMemberIds = { activeGroupMemberIds },
            setGroupMemberIds = { activeGroupMemberIds = it },
            activeCallSession = { activeCallSession },
            setActiveCallSession = { activeCallSession = it },
            activeDomainSession = { activeDomainSession },
            setActiveDomainSession = { activeDomainSession = it },
            clearPendingOfferSdp = { pendingOfferSdp = null },
            clearSignalingIdempotency = { signalingIdempotency.clear() },
            clearInboundSignalingCursors = { inboundSignalingCursors.clear() },
            clearOutboundSignalingCursor = { outboundSignalingCursor.clear() },
            callSessionGate = callSessionGate,
            callSessionMachine = callSessionMachine,
            timersController = timersController,
            signalingIngress = signalingIngress,
            ringingTimeout = ringingTimeout,
            iceRecovery = iceRecovery,
            groupMesh = groupMesh,
            mediaBridge = mediaBridge,
            hangUpSender = hangUpSender,
            foregroundService = foregroundService,
            writeCallLog = { state -> writeCallLog(state) },
        )
    }

    init {
        viewModelScope.launch {
            WebRtcNativeLibraryLoader.progress.collect { pct ->
                _uiState.update { it.copy(nativeDownloadProgress = pct) }
            }
        }
        viewModelScope.launch {
            CallActionBus.hangUpRequests.collect { req ->
                // Drop hang-ups buffered before logout/account switch.
                if (req.sessionGeneration != AppRuntime.currentSessionGeneration) {
                    return@collect
                }
                if (
                    CallReliabilityPolicy.shouldAcceptHangUpAction(activeCallId, req.callId) &&
                    _uiState.value.callState != CallState.IDLE &&
                    _uiState.value.callState != CallState.DISCONNECTED
                ) {
                    endCall(notifyPeer = req.notifyPeer)
                }
            }
        }
    }


    private fun beginCallSession(peerId: String, incoming: Boolean = false): Long {
        signalingIdempotency.clear()
        inboundSignalingCursors.clear()
        outboundSignalingCursor.begin()
        iceRecovery.resetAttempts()
        val snapshot = if (incoming) {
            callSessionMachine.beginIncoming(peerId)
        } else {
            callSessionMachine.beginOutgoing(peerId)
        }
        activeDomainSession = snapshot.epoch
        return callSessionGate.begin().also { activeCallSession = it }
    }

    private fun isCurrentCallSession(session: Long, manager: WebRTCManager? = null): Boolean =
        callSessionGate.isCurrent(session) && !endingCall && (manager == null || webRTCManager === manager)

    private fun newCallId(): String = "call_${UUID.randomUUID().toString().replace("-", "")}"

    fun startCall(contactId: String, contactName: String, contactAvatar: String?, callType: CallType) =
        outgoingCallController.startCall(contactId, contactName, contactAvatar, callType)

    fun startGroupCall(chatId: String, memberIds: List<String>, type: CallType) =
        outgoingCallController.startGroupCall(chatId, memberIds, type)


    fun prepareIncomingCall(
        contactId: String,
        contactName: String,
        contactAvatar: String?,
        callType: CallType,
        offerSdp: String,
        callId: String = "",
        groupId: String = "",
        groupMemberIds: List<String> = emptyList()
    ) = incomingCallController.prepareIncomingCall(contactId, contactName, contactAvatar, callType, offerSdp, callId, groupId, groupMemberIds)

    fun answerCall(contactId: String? = null, contactName: String? = null, contactAvatar: String? = null, callType: CallType? = null, offerSdp: String? = null) =
        incomingCallController.answerCall(contactId, contactName, contactAvatar, callType, offerSdp)

    fun rejectIncomingCall() = incomingCallController.rejectIncomingCall()

    fun hangUp() {
        endCall(notifyPeer = true)
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun toggleMute(muted: Boolean) { mediaBridge.toggleMute(muted) }
    fun toggleVideo(enabled: Boolean) { mediaBridge.toggleVideo(enabled) }
    fun switchCamera() { mediaBridge.switchCamera() }
    fun selectAudioRoute(route: CallAudioRoute) { mediaBridge.selectAudioRoute(route) }

    fun attachLocalRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        mediaBridge.attachLocalRenderer(renderer)
    }
    fun attachRemoteRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        mediaBridge.attachRemoteRenderer(renderer)
    }
    fun attachGroupRemoteRenderer(userId: String, renderer: org.webrtc.SurfaceViewRenderer) {
        mediaBridge.attachGroupRemoteRenderer(userId, renderer)
    }
    fun detachGroupRemoteRenderer(userId: String, renderer: org.webrtc.SurfaceViewRenderer) {
        mediaBridge.detachGroupRemoteRenderer(userId, renderer)
    }
    fun detachLocalRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        mediaBridge.detachLocalRenderer(renderer)
    }
    fun detachRemoteRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        mediaBridge.detachRemoteRenderer(renderer)
    }

    // G372：通话记录写入抽到 CallLogWriter（纯搬移不改判断）。
    private val callLogWriter by lazy {
        CallLogWriter(
            application = app,
            currentState = { _uiState.value },
            activeCallId = { activeCallId },
            callLogOwnerUserId = { callLogOwnerUserId },
        )
    }

    private fun writeCallLog(state: com.maodouchat.call.CallLogStore.State) =
        callLogWriter.writeCallLog(state)


    // 拆卸收尾簇已搬入 CallTeardownController；此处只留同签名委托，各 controller 的 endCall 接线不变。
    private fun endCall(notifyPeer: Boolean, errorMessage: String? = null, logMissed: Boolean = true) =
        teardownController.endCall(notifyPeer, errorMessage, logMissed)

    override fun onCleared() {
        // 仍在通话中则必须通知对端；hang-up 用 applicationScope，不依赖即将取消的 viewModelScope
        val shouldNotifyPeer = _uiState.value.callState != CallState.IDLE &&
            _uiState.value.callState != CallState.DISCONNECTED
        timersController.cancelTimers()
        signalingIngress.stop()
        ringingTimeout.cancel()
        iceRecovery.cancel()
        // 8.46 修复：无条件停止前台服务——若已有挂断在途（endingCall=true），endCall 开头
        // 直接 return，末尾的 foregroundService.stop() 被跳过，通话前台通知/服务残留到系统回收。
        foregroundService.stop()
        endCall(notifyPeer = shouldNotifyPeer)
        super.onCleared()
    }
}
