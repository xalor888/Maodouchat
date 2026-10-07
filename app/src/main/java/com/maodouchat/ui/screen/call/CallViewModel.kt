package com.maodouchat.ui.screen.call

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.core.realtime.RealtimeDomainEvent
import com.maodouchat.call.CallActionBus
import com.maodouchat.session.AppRuntime
import com.maodouchat.call.CallMediaBridge
import com.maodouchat.call.CallSignalingAdmissionPolicy
import com.maodouchat.call.CallSignalingIdempotencyStore
import com.maodouchat.call.CallSignalingOrderPolicy
import com.maodouchat.call.CallSignalingOutboundCursor
import com.maodouchat.call.CallSystemIntegration
import com.maodouchat.call.GroupCallCapabilities
import com.maodouchat.call.IncomingCallCoordinator
import com.maodouchat.call.MissedCallRecorder
import com.maodouchat.call.MissedCallTimeoutPolicy
import com.maodouchat.call.WebRtcNativeLibraryLoader
import com.maodouchat.webrtc.CallState
import com.maodouchat.webrtc.CallType
import com.maodouchat.webrtc.CallReliabilityPolicy
import com.maodouchat.webrtc.CallSessionGate
import com.maodouchat.webrtc.CallAudioRoute
import com.maodouchat.webrtc.GroupPeerConnectionState
import com.maodouchat.webrtc.WebRTCManager
import com.maodouchat.webrtc.WebRTCSignaling
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.IceCandidate
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

    /** 通话中 TURN 凭据刷新 job（8.35：短期凭据 1h 过期后热替换 ICE 配置）。 */
    private var pollingJob: Job? = null
    private var webSocketJob: Job? = null
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
    // 8.56：群 mesh 中「成员已先接听、本端 manager 未建」时缓冲其 offer，接听后统一 flush，
    // 否则该边 offer 被静默丢弃导致成员连接永久缺失
    private val pendingGroupOffers = mutableMapOf<String, String>()

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

    private val outgoingCallController by lazy {
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
            flushPendingGroupOffers = { manager, session -> flushPendingGroupOffers(manager, session) },
            signalingSender = signalingSender,
            ringingTimeout = ringingTimeout,
            foregroundService = foregroundService,
            observeSignaling = ::observeSignaling,
            endCall = ::endCall,
        )
    }

    private val incomingCallController by lazy {
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
            flushPendingGroupOffers = { manager, session -> flushPendingGroupOffers(manager, session) },
            callSessionMachine = callSessionMachine,
            activeDomainSession = { activeDomainSession },
            ringingTimeout = ringingTimeout,
            foregroundService = foregroundService,
            observeSignaling = ::observeSignaling,
            endCall = ::endCall,
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

    /**
     * 8.56：群 mesh——本端 manager 就绪后统一处理被缓冲的成员边 offer
     * （成员先接听、本端尚在 RINGING/CALLING 时，offer 曾被打消）。
     */
    private fun flushPendingGroupOffers(manager: WebRTCManager, session: Long) {
        if (pendingGroupOffers.isEmpty()) return
        val entries = pendingGroupOffers.toList()
        pendingGroupOffers.clear()
        entries.forEach { (fromUserId, sdp) ->
            if (manager.hasGroupPeer(fromUserId)) return@forEach
            groupMesh.scheduleGroupPeerTimeout(fromUserId)
            manager.acceptGroupOffer(
                peerUserId = fromUserId,
                remoteOfferSdp = sdp,
                type = _uiState.value.callType,
                onIceCandidate = { candidate ->
                    if (isCurrentCallSession(session, manager)) signalingSender.sendIceCandidate(fromUserId, candidate)
                },
                onAnswerCreated = { sdp2 ->
                    if (isCurrentCallSession(session, manager)) signalingSender.sendSdp(fromUserId, "answer", sdp2)
                }
            )
        }
    }

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

    /**
     * 挂断
     */
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

    /** UI 层创建 SurfaceViewRenderer 后调用，将渲染器连接到 WebRTCManager */
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

    private fun observeSignaling() {
        // 独立管理两个 job：一个死掉不影响另一个；避免"WS  collector 死了但 polling 还活着导致永远不重连"
        if (webSocketJob?.isActive != true) {
            val signalOwnerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            val dispatcher = AppRuntime.realtimeDispatcherOrNull(app)
            if (dispatcher != null) {
                webSocketJob = viewModelScope.launch {
                    dispatcher.allEvents.collect { event ->
                        if (
                            signalOwnerUserId.isBlank() ||
                            !com.maodouchat.security.BackgroundSessionGate.mayContinue(
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
                                    _uiState.value.callState == CallState.CALLING
                                ) {
                                    endCall(
                                        notifyPeer = false,
                                        errorMessage = text(R.string.call_rate_limited, event.retryAfterSeconds ?: 60)
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
            pollingJob = viewModelScope.launch {
                while (_uiState.value.callState != CallState.DISCONNECTED) {
                    delay(2000)
                    if (
                        pollOwnerUserId.isBlank() ||
                        !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = pollOwnerUserId,
                        )
                    ) {
                        continue
                    }
                    val liveToken = token
                    if (liveToken.isBlank()) continue
                    WebRTCSignaling.fetchPending(liveToken)
                        .onSuccess { messages ->
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = pollOwnerUserId)) {
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
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = pollOwnerUserId)) {
                                return@onFailure
                            }
                            _uiState.update { it.copy(errorMessage = text(R.string.call_fetch_signaling_failed, failureReason(error))) }
                        }
                }
            }
        }
    }

    fun startGroupCall(chatId: String, memberIds: List<String>, type: CallType) =
        outgoingCallController.startGroupCall(chatId, memberIds, type)

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
        val currentState = _uiState.value
        val expectedContactId = currentState.contactId
        val normalizedType = CallReliabilityPolicy.normalizeSignalingType(type)
        val isGroup = currentState.isGroupCall
        val cursorKey = "$callId|$fromUserId"
        when (
            CallSignalingAdmissionPolicy.admit(
                isGroupCall = isGroup,
                expectedContactId = expectedContactId,
                fromUserId = fromUserId,
                activeCallId = activeCallId,
                incomingCallId = callId,
                activeGroupId = activeGroupId,
                incomingGroupId = groupId,
                activeMembers = meshGroupMemberIds,
                incomingMembers = groupMemberIds,
                signalType = type,
                incomingEpoch = epoch,
                incomingSequence = sequence,
                lastAcceptedCursor = inboundSignalingCursors[cursorKey],
            )
        ) {
            CallSignalingAdmissionPolicy.Decision.DROP -> return
            CallSignalingAdmissionPolicy.Decision.BUSY_REJECT -> {
                if (fromUserId.isNotBlank()) {
                    signalingSender.sendSignalWithFallback(
                        fromUserId,
                        "busy",
                        "",
                        text(R.string.call_notify_busy_failed),
                        callId,
                        groupId,
                        groupMemberIds
                    )
                }
                return
            }
            CallSignalingAdmissionPolicy.Decision.ACCEPT -> Unit
        }

        if (!signalingIdempotency.remember(
                callId = callId,
                fromUserId = fromUserId,
                type = normalizedType,
                payload = payload,
                idempotencyKey = idempotencyKey,
            )
        ) {
            return
        }
        if (epoch != 0L || sequence != 0L) {
            inboundSignalingCursors[cursorKey] = CallSignalingOrderPolicy.Cursor(epoch, sequence)
        }

        try {
            when (normalizedType) {
                "answer" -> {
                    val st = _uiState.value
                    if (st.callState == CallState.DISCONNECTED || st.callState == CallState.IDLE) return
                    ringingTimeout.cancel()
                    if (st.isGroupCall) {
                        groupMesh.cancelInviteTimeout(fromUserId)
                        webRTCManager?.handleGroupAnswer(fromUserId, payload)
                    } else {
                        webRTCManager?.handleAnswer(payload)
                    }
                    // BUG 3 fix: 不在此处设 CONNECTED，等 ICE onIceConnectionChange 回调设
                    _uiState.update { it.copy(isInitializing = false) }
                }
                "ice-candidate" -> {
                    val parts = payload.split("|", limit = 3)
                    if (parts.size == 3) {
                        val sdpMLineIndex = parts[1].toIntOrNull()
                        if (sdpMLineIndex != null) {
                            val candidate = IceCandidate(parts[0], sdpMLineIndex, parts[2])
                            if (currentState.isGroupCall) {
                                webRTCManager?.addGroupIceCandidate(fromUserId, candidate)
                            } else {
                                webRTCManager?.addIceCandidate(candidate)
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
                            viewModelScope.launch {
                                try {
                                    MissedCallRecorder.recordRingTimeout(
                                        context = app,
                                        signalingCallId = activeCallId.ifBlank { callId },
                                        fromUserId = currentState.contactId.ifBlank { fromUserId },
                                        callerName = currentState.contactName,
                                        isVideo = currentState.callType == CallType.VIDEO,
                                        isGroup = currentState.isGroupCall,
                                    )
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    android.util.Log.w(
                                        "CallViewModel",
                                        "missed-call record on group initiator hang-up failed",
                                        error
                                    )
                                }
                            }
                            IncomingCallCoordinator.clear()
                            endCall(notifyPeer = false)
                        } else {
                            webRTCManager?.removeGroupPeer(fromUserId)
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
                            viewModelScope.launch {
                                try {
                                    MissedCallRecorder.recordRingTimeout(
                                        context = app,
                                        signalingCallId = activeCallId.ifBlank { callId },
                                        fromUserId = currentState.contactId.ifBlank { fromUserId },
                                        callerName = currentState.contactName,
                                        isVideo = currentState.callType == CallType.VIDEO,
                                    )
                                } catch (error: kotlinx.coroutines.CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    android.util.Log.w(
                                        "CallViewModel",
                                        "missed-call record on peer hang-up failed",
                                        error
                                    )
                                }
                            }
                            IncomingCallCoordinator.clear()
                        }
                        endCall(notifyPeer = false)
                    }
                }
                "reject" -> {
                    if (currentState.isGroupCall) {
                        webRTCManager?.removeGroupPeer(fromUserId)
                        groupMesh.markGroupPeerTerminal(fromUserId, GroupPeerConnectionState.REJECTED)
                    } else {
                        endCall(notifyPeer = false, errorMessage = text(R.string.call_peer_rejected))
                    }
                }
                "busy" -> {
                    if (currentState.isGroupCall) {
                        webRTCManager?.removeGroupPeer(fromUserId)
                        groupMesh.markGroupPeerTerminal(fromUserId, GroupPeerConnectionState.BUSY)
                    } else {
                        endCall(notifyPeer = false, errorMessage = text(R.string.call_peer_busy))
                    }
                }
                "offer" -> {
                    val st = _uiState.value
                    if (st.callState == CallState.IDLE || st.callState == CallState.DISCONNECTED) {
                        val inferredType = CallType.detectFromSdp(payload)
                        prepareIncomingCall(fromUserId, fromUserId, null, inferredType, payload, callId, groupId, groupMemberIds)
                    } else if (
                        st.isGroupCall &&
                        groupId == activeGroupId &&
                        fromUserId in activeGroupMemberIds &&
                        webRTCManager?.hasGroupPeer(fromUserId) != true
                    ) {
                        groupMesh.scheduleGroupPeerTimeout(fromUserId)
                        val manager = webRTCManager
                        if (manager == null) {
                            // 8.56：本端 manager 尚未创建（成员先接听）——缓冲该边 offer，
                            // 接听/建 manager 后统一 flush，避免边永久丢失
                            pendingGroupOffers[fromUserId] = payload
                        } else {
                            val session = activeCallSession
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
                            text(R.string.call_notify_busy_failed),
                            callId,
                            groupId,
                            groupMemberIds
                        )
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = text(R.string.call_handle_signaling_failed, e.message ?: text(R.string.call_unknown_error))) }
        }
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


    private fun endCall(notifyPeer: Boolean, errorMessage: String? = null, logMissed: Boolean = true) {
        if (endingCall) return
        endingCall = true
        callSessionMachine.beginEnding(activeDomainSession)
        callSessionGate.invalidate(activeCallSession)
        val contactId = _uiState.value.contactId
        timersController.cancelTimers()
        pollingJob?.cancel()
        webSocketJob?.cancel()
        ringingTimeout.cancel()
        iceRecovery.cancel()
        groupMesh.cancelAllGroupJobs()
        pollingJob = null
        webSocketJob = null

        mediaBridge.release()
        pendingOfferSdp = null
        // 8.56：清群 mesh 边 offer 缓冲——否则过期 offer 会串入下一通点对点通话（flush 误建 group peer）
        pendingGroupOffers.clear()

        // Snapshot before clearing; cancel only the *incoming* tray slot.
        // Missed-call notify id is salted separately so this cannot wipe a just-posted
        // missed entry after ring-timeout / peer-hang-up record.
        val hangupCallId = activeCallId
        if (hangupCallId.isNotBlank()) {
            com.maodouchat.notification.CallNotificationService.cancelIncomingCall(app, hangupCallId)
            // 销毁系统级 Telecom Connection，避免应用内挂断后系统残留「活跃通话」(幽灵来电)
            com.maodouchat.telecom.MaodouchatConnectionService.finishConnection(hangupCallId)
        }
        // 8.52：挂断前按最终状态回写（接通→补时长；未接→保持 MISSED）
        if (logMissed) {
            writeCallLog(
                if (_uiState.value.callState == CallState.CONNECTED)
                    com.maodouchat.call.CallLogStore.State.ANSWERED
                else
                    com.maodouchat.call.CallLogStore.State.MISSED
            )
        } else {
            // 8.53：主动拒接（logMissed=false）——仅回写已接通话时长，不落 MISSED
            if (_uiState.value.callState == CallState.CONNECTED) {
                writeCallLog(com.maodouchat.call.CallLogStore.State.ANSWERED)
            }
        }

        if (notifyPeer && token.isNotBlank()) {
            // 先快照 callId/目标，再清会话字段，避免 send 协程读到空 activeCallId
            val hangupGroupId = activeGroupId
            val hangupMembers = meshGroupMemberIds
            val targets = if (_uiState.value.isGroupCall) activeGroupMemberIds else setOf(contactId)
            targets.filter(String::isNotBlank).forEach { targetId ->
                hangUpSender.sendHangUpDurable(
                    toUserId = targetId,
                    callId = hangupCallId,
                    groupId = hangupGroupId,
                    groupMembers = hangupMembers
                )
            }
        }
        activeGroupMemberIds = emptySet()
        activeGroupId = ""
        meshGroupMemberIds = emptyList()
        activeCallId = ""
        activeCallSession = 0L
        callSessionMachine.finish(activeDomainSession)
        activeDomainSession = 0L
        signalingIdempotency.clear()
        inboundSignalingCursors.clear()
        outboundSignalingCursor.clear()

        _uiState.update {
            it.copy(
                callState = CallState.DISCONNECTED,
                isInitializing = false,
                duration = "00:00",  // 重置 duration 避免残影
                networkReconnecting = false,
                networkStats = NetworkQuality.UNKNOWN,
                iceStunOnly = false,
                availableAudioRoutes = emptySet(),
                selectedAudioRoute = null,
                groupParticipants = emptyList(),
                errorMessage = errorMessage ?: it.errorMessage
            )
        }
        foregroundService.stop()
    }

    /**
     * 定期读取 PeerConnection 的 RTC 统计，估算当前链路质量，结果写入 networkStats。
     * 不阻塞 UI，主观评价值 GOOD/FAIR/POOR。
     */


    override fun onCleared() {
        // 仍在通话中则必须通知对端；hang-up 用 applicationScope，不依赖即将取消的 viewModelScope
        val shouldNotifyPeer = _uiState.value.callState != CallState.IDLE &&
            _uiState.value.callState != CallState.DISCONNECTED
        timersController.cancelTimers()
        pollingJob?.cancel()
        webSocketJob?.cancel()
        ringingTimeout.cancel()
        iceRecovery.cancel()
        // 8.46 修复：无条件停止前台服务——若已有挂断在途（endingCall=true），endCall 开头
        // 直接 return，末尾的 foregroundService.stop() 被跳过，通话前台通知/服务残留到系统回收。
        foregroundService.stop()
        endCall(notifyPeer = shouldNotifyPeer)
        super.onCleared()
    }
}
