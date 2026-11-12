package com.maodouchat.ui.screen.call

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.call.CallMediaBridge
import com.maodouchat.core.session.CallSignalingIdempotencyStore
import com.maodouchat.core.session.Cursor
import com.maodouchat.core.session.CallSignalingOutboundCursor
import com.maodouchat.core.session.CallSessionResetController
import com.maodouchat.session.AppRuntime
import com.maodouchat.security.BackgroundSessionGate
import com.maodouchat.call.CallSystemIntegration
import com.maodouchat.call.MissedCallRecorder
import com.maodouchat.call.WebRtcNativeLibraryLoader
import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.CallSessionGate
import com.maodouchat.core.model.CallAudioRoute
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.IncomingCallCoordinator
import com.maodouchat.domain.calls.CallConnectionStats
import com.maodouchat.domain.calls.CallForegroundServiceController
import com.maodouchat.domain.calls.CallIncomingCallController
import com.maodouchat.domain.calls.CallLifecycleController
import com.maodouchat.domain.calls.CallOutgoingCallController
import com.maodouchat.domain.calls.CallSessionController
import com.maodouchat.domain.calls.CallSessionTimersController
import com.maodouchat.domain.calls.CallSignalingIngressController
import com.maodouchat.domain.calls.CallTeardownController
import com.maodouchat.notification.CallNotificationService
import com.maodouchat.telecom.MaodouchatConnectionService
import com.maodouchat.util.RuntimeFlags
import com.maodouchat.R
import com.maodouchat.webrtc.WebRTCManager
import com.maodouchat.webrtc.WebRTCSignaling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class CallViewModel(application: Application) : AndroidViewModel(application) {

    private val mediaBridge = CallMediaBridge()
    private val webRTCManager: WebRTCManager? get() = mediaBridge.manager
    private val token: String get() = com.maodouchat.session.CurrentSession.snapshot().token ?: ""

    private val app: Application get() = getApplication()

    // 文案簇在 CallErrorMessages；接线直接引它的方法引用，VM 不再转手。
    private val errorMessages = CallErrorMessages(application)

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private var pendingOfferSdp: String? = null
    private var activeCallId: String = ""
    private val callSessionGate = CallSessionGate()
    private val callSessionMachine = com.maodouchat.core.session.CallSessionMachine()
    private val callSystemIntegration = CallSystemIntegration(application)
    private val foregroundService by lazy {
        CallForegroundServiceController(
            startCallForeground = callSystemIntegration::startCallForeground, stopCallForeground = callSystemIntegration::stopCallForeground,
            currentState = { _uiState.value }, activeCallId = { activeCallId },
            unknownCallerLabel = errorMessages.text(R.string.call_unknown_caller),
        )
    }
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
    private val hangUpSender = createCallHangUpSender(outboundSignalingCursor)
    /** Per sender last accepted (epoch, sequence) for the active call. */
    private val inboundSignalingCursors = mutableMapOf<String, Cursor>()
    // 8.55：通话开始时的账号快照——writeCallLog 用其作 expectedUserId 守卫，通话中异地登出换号后旧通话不写进新账号 key
    private var callLogOwnerUserId: String = ""

    private companion object {
        const val RINGING_TIMEOUT_MS = 30_000L
    }

    private val timersController by lazy {
        CallSessionTimersController(
            scope = viewModelScope,
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            getConnectionStats = { webRTCManager?.getConnectionStatsSnapshot()?.let { CallConnectionStats(rttMs = it.rttMs, packetLossPercent = it.packetLossPercent) } },
            refreshIceServers = { servers -> webRTCManager?.refreshIceServers(servers) },
            fetchIceServers = { liveToken -> WebRTCSignaling.fetchIceServers(liveToken) },
            elapsedRealtimeMs = { SystemClock.elapsedRealtime() },
            activeCallSession = { activeCallSession },
            callSessionGate = callSessionGate,
            token = { token },
            onLogAnswered = { writeCallLog(com.maodouchat.core.model.State.ANSWERED) },
        )
    }

    private val signalingSender by lazy {
        createCallSignalingSender(
            scope = viewModelScope,
            token = { token },
            activeCallId = { activeCallId },
            activeGroupId = { activeGroupId },
            meshGroupMemberIds = { meshGroupMemberIds },
            activeCallSession = { activeCallSession },
            callSessionGate = callSessionGate,
            outboundSignalingCursor = outboundSignalingCursor,
            updateState = { transform -> _uiState.update(transform) },
            errorMessages = errorMessages,
            failureReason = errorMessages::failureReason,
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
            text = errorMessages::text,
            onNoActivePeers = { message -> endCall(notifyPeer = false, errorMessage = message) },
        )
    }

    private val iceRecovery by lazy {
        createCallIceRecovery(
            scope = viewModelScope,
            endingCall = { endingCall },
            callSessionMachine = callSessionMachine,
            activeDomainSession = { activeDomainSession },
            startTimers = { timersController.startDurationTimer(); timersController.startNetworkStatsPolling() },
            activeCallSession = { activeCallSession },
            callSessionGate = callSessionGate,
            updateState = { transform -> _uiState.update(transform) },
            currentState = { _uiState.value },
            onIceGiveUp = { message -> endCall(notifyPeer = false, errorMessage = message) },
            errorMessages = errorMessages,
            restartIce = { webRTCManager?.restartIce() },
        )
    }

    private val ringingTimeout by lazy {
        createCallRingingTimeout(
            scope = viewModelScope,
            app = app,
            sessionGate = callSessionGate,
            activeCallSession = { activeCallSession },
            currentState = { _uiState.value },
            activeCallId = { activeCallId },
            timeoutMs = RINGING_TIMEOUT_MS,
            errorMessages = errorMessages,
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
            text = errorMessages::text,
        )
    }

    private val outgoingCallController: CallOutgoingCallController by lazy {
        CallOutgoingCallController(
            scope = viewModelScope,
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            token = { token },
            text = { message, args -> errorMessages.text(callOutgoingCallMessageResId(message), *args) },
            callsEnabled = { RuntimeFlags.isEnabled(app, RuntimeFlags.CALLS) },
            callTypeEnabled = { type -> when (type) { CallType.VIDEO -> RuntimeFlags.isEnabled(app, RuntimeFlags.VIDEO_CALL); CallType.AUDIO, CallType.GROUP -> RuntimeFlags.isEnabled(app, RuntimeFlags.VOICE_CALL) } },
            resetForNewOutgoingCall = sessionResetController::resetForNewOutgoingCall,
            resetForNewGroupCall = sessionResetController::resetForNewGroupCall,
            groupMemberIds = { activeGroupMemberIds },
            setGroupMemberIds = { ids -> activeGroupMemberIds = ids },
            currentOwnerUserId = { com.maodouchat.session.CurrentSession.ownerUserId() },
            writeCallLog = { state -> writeCallLog(state) },
            createWebRtc = { WebRtcCallSignalingWebRtc(webRtcSetup.createWebRtcManager()) },
            configureWebRtc = { session, id -> webRtcSetup.configureReliabilityCallbacks((session as WebRtcCallSignalingWebRtc).manager, id) },
            bindMedia = { session -> mediaBridge.bind((session as WebRtcCallSignalingWebRtc).manager) },
            groupMesh = groupMesh,
            sessionGateIsCurrent = { session -> callSessionGate.isCurrent(session) },
            isCurrentCallSession = { session, webRtc -> isCurrentCallSession(session, (webRtc as? WebRtcCallSignalingWebRtc)?.manager) },
            flushPendingGroupOffers = { webRtc, session -> signalingIngress.flushPendingGroupOffers(webRtc, session) },
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
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            token = { token },
            text = { message, args -> errorMessages.text(callIncomingCallMessageResId(message), *args) },
            cancelIncomingCall = { callId -> CallNotificationService.cancelIncomingCall(app, callId) },
            activeCallId = { activeCallId },
            activeCallSession = { activeCallSession },
            pendingOfferSdp = { pendingOfferSdp },
            clearPendingOfferSdp = { pendingOfferSdp = null },
            resetForNewIncomingCall = sessionResetController::resetForNewIncomingCall,
            markIncomingSessionOwner = sessionResetController::markIncomingSessionOwner,
            signalingSender = signalingSender,
            groupMesh = groupMesh,
            createWebRtc = { WebRtcCallSignalingWebRtc(webRtcSetup.createWebRtcManager()) },
            configureWebRtc = { session, id -> webRtcSetup.configureReliabilityCallbacks((session as WebRtcCallSignalingWebRtc).manager, id) },
            bindMedia = { session -> mediaBridge.bind((session as WebRtcCallSignalingWebRtc).manager) },
            sessionGateIsCurrent = { session -> callSessionGate.isCurrent(session) },
            isCurrentCallSession = { session, webRtc -> isCurrentCallSession(session, (webRtc as? WebRtcCallSignalingWebRtc)?.manager) },
            flushPendingGroupOffers = { webRtc, session -> signalingIngress.flushPendingGroupOffers(webRtc, session) },
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
            currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            token = { token },
            ownerUserId = { com.maodouchat.session.CurrentSession.ownerUserId() },
            mayContinueSession = { userId -> BackgroundSessionGate.mayContinue(expectedUserId = userId) },
            signalingEvents = AppRuntime.realtimeDispatcherOrNull(app)?.allEvents,
            fetchPendingSignaling = { liveToken -> WebRTCSignaling.fetchPending(liveToken) },
            isCurrentCallSession = { session, webRtc -> isCurrentCallSession(session, (webRtc as? WebRtcCallSignalingWebRtc)?.manager) },
            lastAcceptedCursor = { key -> inboundSignalingCursors[key] },
            recordAcceptedCursor = { key, cursor -> inboundSignalingCursors[key] = cursor },
            rememberSignaling = { callId, fromUserId, type, payload, idempotencyKey ->
                signalingIdempotency.remember(callId, fromUserId, type, payload, idempotencyKey)
            },
            activeCallId = { activeCallId },
            activeGroupId = { activeGroupId },
            meshGroupMemberIds = { meshGroupMemberIds },
            groupMemberIds = { activeGroupMemberIds },
            activeCallSession = { activeCallSession },
            webRtc = { webRTCManager?.let { WebRtcCallSignalingWebRtc(it) } },
            groupMesh = groupMesh,
            signalingSender = signalingSender,
            ringingTimeout = ringingTimeout,
            prepareIncomingCall = { contactId, contactName, contactAvatar, callType, offerSdp, callId, groupId, groupMemberIds ->
                incomingCallController.prepareIncomingCall(contactId, contactName, contactAvatar, callType, offerSdp, callId, groupId, groupMemberIds)
            },
            recordMissedCall = { signalingCallId, fromUserId, callerName, isVideo, isGroup -> MissedCallRecorder.recordRingTimeout(app, signalingCallId, fromUserId, callerName, isVideo, isGroup = isGroup) },
            clearIncomingCall = { IncomingCallCoordinator.clear() },
            logWarning = { message, error -> Log.w("CallViewModel", message, error) },
            text = { message, args -> errorMessages.text(callSignalingIngressMessageResId(message), *args) },
            failureReason = errorMessages::failureReason,
            endCall = { notifyPeer, errorMessage -> endCall(notifyPeer, errorMessage) },
        )
    }

    private val teardownController: CallTeardownController by lazy {
        CallTeardownController(
            cancelIncomingCall = { callId -> CallNotificationService.cancelIncomingCall(app, callId) },
            finishTelecomConnection = { MaodouchatConnectionService.finishConnection(it) },
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
            stopSignalingIngress = { signalingIngress.stop() },
            clearPendingGroupOffers = { signalingIngress.clearPendingGroupOffers() },
            ringingTimeout = ringingTimeout,
            iceRecovery = iceRecovery,
            cancelGroupJobs = { groupMesh.cancelAllGroupJobs() },
            releaseMedia = { mediaBridge.release() },
            hangUpSender = hangUpSender,
            foregroundService = foregroundService,
            writeCallLog = { state -> writeCallLog(state) },
        )
    }

    private val sessionController by lazy {
        CallSessionController(
            callSessionGate = callSessionGate, callSessionMachine = callSessionMachine,
            resetIceRecoveryAttempts = { iceRecovery.resetAttempts() },
            clearSignalingIdempotency = { signalingIdempotency.clear() }, clearInboundSignalingCursors = { inboundSignalingCursors.clear() },
            beginOutboundSignalingCursor = { outboundSignalingCursor.begin() },
            setActiveDomainSession = { activeDomainSession = it },
            setActiveCallSession = { activeCallSession = it },
            endingCall = { endingCall },
            currentManager = { webRTCManager },
        )
    }

    private val sessionResetController by lazy {
        CallSessionResetController(
            beginCallSession = { peerId, incoming -> beginCallSession(peerId, incoming) },
            newCallId = ::newCallId,
            setEndingCall = { endingCall = it },
            setActiveCallId = { activeCallId = it },
            setActiveGroupId = { activeGroupId = it },
            setMeshGroupMemberIds = { meshGroupMemberIds = it },
            setGroupMemberIds = { activeGroupMemberIds = it },
            setPendingOfferSdp = { pendingOfferSdp = it },
            setCallLogOwnerUserId = { callLogOwnerUserId = it },
            ownerUserId = { com.maodouchat.session.CurrentSession.ownerUserId() },
        )
    }

    private val lifecycleController by lazy {
        CallLifecycleController(
            scope = viewModelScope, currentState = { _uiState.value },
            updateState = { transform -> _uiState.update(transform) },
            activeCallId = { activeCallId },
            cancelTimers = { timersController.cancelTimers() },
            stopSignalingIngress = { signalingIngress.stop() },
            cancelRingingTimeout = { ringingTimeout.cancel() },
            cancelIceRecovery = { iceRecovery.cancel() },
            stopForegroundService = { foregroundService.stop() },
            endCall = { notifyPeer -> endCall(notifyPeer) },
            nativeDownloadProgress = WebRtcNativeLibraryLoader.progress,
            currentSessionGeneration = { AppRuntime.currentSessionGeneration },
        )
    }

    private val mediaController by lazy { CallMediaController(mediaBridge = mediaBridge) }

    init { lifecycleController.onStart() }

    private fun beginCallSession(peerId: String, incoming: Boolean = false): Long = sessionController.beginCallSession(peerId, incoming)

    private fun isCurrentCallSession(session: Long, manager: WebRTCManager? = null): Boolean = sessionController.isCurrentCallSession(session, manager)

    private fun newCallId(): String = sessionController.newCallId()

    fun startCall(contactId: String, contactName: String, contactAvatar: String?, callType: CallType) = outgoingCallController.startCall(contactId, contactName, contactAvatar, callType)

    fun startGroupCall(chatId: String, memberIds: List<String>, type: CallType) = outgoingCallController.startGroupCall(chatId, memberIds, type)

    fun prepareIncomingCall(contactId: String, contactName: String, contactAvatar: String?, callType: CallType, offerSdp: String, callId: String = "", groupId: String = "",
        groupMemberIds: List<String> = emptyList()
    ) = incomingCallController.prepareIncomingCall(contactId, contactName, contactAvatar, callType, offerSdp, callId, groupId, groupMemberIds)

    fun answerCall(contactId: String? = null, contactName: String? = null, contactAvatar: String? = null, callType: CallType? = null, offerSdp: String? = null) = incomingCallController.answerCall(contactId, contactName, contactAvatar, callType, offerSdp)

    fun rejectIncomingCall() = incomingCallController.rejectIncomingCall()

    fun hangUp() = endCall(notifyPeer = true)

    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    fun toggleMute(muted: Boolean) = mediaController.toggleMute(muted)
    fun toggleVideo(enabled: Boolean) = mediaController.toggleVideo(enabled)
    fun switchCamera() = mediaController.switchCamera()
    fun selectAudioRoute(route: CallAudioRoute) = mediaController.selectAudioRoute(route)

    fun attachLocalRenderer(renderer: org.webrtc.SurfaceViewRenderer) = mediaController.attachLocalRenderer(renderer)
    fun attachRemoteRenderer(renderer: org.webrtc.SurfaceViewRenderer) = mediaController.attachRemoteRenderer(renderer)
    fun attachGroupRemoteRenderer(userId: String, renderer: org.webrtc.SurfaceViewRenderer) = mediaController.attachGroupRemoteRenderer(userId, renderer)
    fun detachGroupRemoteRenderer(userId: String, renderer: org.webrtc.SurfaceViewRenderer) = mediaController.detachGroupRemoteRenderer(userId, renderer)
    fun detachLocalRenderer(renderer: org.webrtc.SurfaceViewRenderer) = mediaController.detachLocalRenderer(renderer)
    fun detachRemoteRenderer(renderer: org.webrtc.SurfaceViewRenderer) = mediaController.detachRemoteRenderer(renderer)

    // G372：通话记录写入抽到 CallLogWriter（纯搬移不改判断）。
    private val callLogWriter by lazy {
        createCallLogWriter(
            app = app,
            currentState = { _uiState.value },
            activeCallId = { activeCallId },
            callLogOwnerUserId = { callLogOwnerUserId },
        )
    }

    private fun writeCallLog(state: com.maodouchat.core.model.State) = callLogWriter.writeCallLog(state)

    // 拆卸收尾簇已搬入 CallTeardownController；此处只留同签名委托，各 controller 的 endCall 接线不变。
    private fun endCall(notifyPeer: Boolean, errorMessage: String? = null, logMissed: Boolean = true) = teardownController.endCall(notifyPeer, errorMessage, logMissed)

    override fun onCleared() {
        lifecycleController.onCleared()
        super.onCleared()
    }
}
