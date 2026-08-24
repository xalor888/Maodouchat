package com.maodouchat.ui.screen.call

import com.maodouchat.util.RuntimeFlags
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maodouchat.R
import com.maodouchat.network.TokenManager
import com.maodouchat.network.ApiService
import com.maodouchat.network.WebSocketClient
import com.maodouchat.network.WebSocketEvent
import com.maodouchat.service.CallForegroundService
import com.maodouchat.call.CallActionBus
import com.maodouchat.call.IncomingCallCoordinator
import com.maodouchat.call.MissedCallRecorder
import com.maodouchat.call.MissedCallTimeoutPolicy
import com.maodouchat.call.WebRtcNativeLibraryLoader
import com.maodouchat.webrtc.CallState
import com.maodouchat.webrtc.CallType
import com.maodouchat.webrtc.CallReliabilityPolicy
import com.maodouchat.webrtc.IceReconnectAction
import com.maodouchat.webrtc.CallNetworkQualityPolicy
import com.maodouchat.webrtc.CallSessionGate
import com.maodouchat.webrtc.CallIceServer
import com.maodouchat.webrtc.CallAudioRoute
import com.maodouchat.webrtc.GroupPeerConnectionState
import com.maodouchat.webrtc.GroupCallPolicy
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
import org.webrtc.SessionDescription
import java.util.UUID

/** WebRTC 原生库加载失败（下载/预加载），用于向用户呈现友好错误而非原生 UnsatisfiedLinkError。 */
class WebRtcNativeLoadException(message: String) : Exception(message)

data class CallUiState(
    val contactId: String = "",
    val contactName: String = "",
    val contactAvatar: String? = null,
    val callType: CallType = CallType.AUDIO,
    val callState: CallState = CallState.IDLE,
    val isIncoming: Boolean = false,
    val isGroupCall: Boolean = false,
    val duration: String = "00:00",
    val isInitializing: Boolean = false,
    val networkReconnecting: Boolean = false,
    val networkStats: NetworkQuality = NetworkQuality.UNKNOWN,
    /** True when ICE uses public STUN only (no TURN credentials). */
    val iceStunOnly: Boolean = false,
    val availableAudioRoutes: Set<CallAudioRoute> = emptySet(),
    val selectedAudioRoute: CallAudioRoute? = null,
    val groupParticipants: List<GroupCallParticipantUi> = emptyList(),
    val errorMessage: String? = null,
    /** 0–100 while downloading the self-hosted WebRTC .so; 0 when idle. */
    val nativeDownloadProgress: Int = 0
)

/** 通话链路质量分级，供顶部小条 + ICE reconnect 提示使用 */
enum class NetworkQuality { GOOD, FAIR, POOR, UNKNOWN }

data class GroupCallParticipantUi(
    val userId: String,
    val name: String = userId,
    val avatar: String? = null,
    val connectionState: GroupPeerConnectionState = GroupPeerConnectionState.CONNECTING,
    val videoAvailable: Boolean = false
)

class CallViewModel(application: Application) : AndroidViewModel(application) {

    private val tokenManager = TokenManager.getInstance(application)
    private var webRTCManager: WebRTCManager? = null
    private val token: String get() = tokenManager.getToken() ?: ""
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

    private var durationJob: Job? = null
    /** 通话中 TURN 凭据刷新 job（8.35：短期凭据 1h 过期后热替换 ICE 配置）。 */
    private var iceRefreshJob: Job? = null
    private var pollingJob: Job? = null
    private var webSocketJob: Job? = null
    private var ringingTimeoutJob: Job? = null
    private var networkStatsJob: Job? = null
    private var iceReconnectJob: Job? = null
    // 8.56：WebRTC 原生回调线程与主线程并发访问——volatile 保证可见性，避免读到旧值误走重连/重复挂断
    @Volatile
    private var iceRestartAttempts = 0
    private val groupReconnectJobs = mutableMapOf<String, Job>()
    private val groupInviteTimeoutJobs = mutableMapOf<String, Job>()
    private var pendingOfferSdp: String? = null
    private var activeCallId: String = ""
    private val callSessionGate = CallSessionGate()
    private var activeCallSession: Long = 0L
    private var activeGroupId: String = ""
    private var meshGroupMemberIds: List<String> = emptyList()
    // 8.56：volatile——onIceConnectionChange 等在 WebRTC signaling 线程回调，与主线程 endCall 并发
    @Volatile
    private var endingCall = false
    private var activeGroupMemberIds: Set<String> = emptySet()
    private val handledSignalingMessages = LinkedHashSet<String>()
    // 8.55：通话开始时的账号快照——writeCallLog 用其作 expectedUserId 守卫，
    // 通话中异地登出换号后旧通话不写进新账号 key
    private var callLogOwnerUserId: String = ""
    // 8.56：群 mesh 中「成员已先接听、本端 manager 未建」时缓冲其 offer，接听后统一 flush，
    // 否则该边 offer 被静默丢弃导致成员连接永久缺失
    private val pendingGroupOffers = mutableMapOf<String, String>()

    private companion object {
        const val RINGING_TIMEOUT_MS = 30_000L
        const val STATS_POLL_INTERVAL_MS = 4_000L
        /** TURN 短期凭据 TTL 1h：每 30 分钟刷新一次，留足余量（8.35）。 */
        const val ICE_REFRESH_INTERVAL_MS = 30L * 60L * 1_000L
    }

    /**
     * 启动前台服务，确保通话期间进程不被轻易回收
     */
    private fun startForegroundService() {
        val state = _uiState.value
        CallForegroundService.start(
            app,
            state.contactName.ifBlank { text(R.string.call_unknown_caller) },
            state.callType == CallType.VIDEO,
            activeCallId
        )
    }

    private fun stopForegroundService() {
        CallForegroundService.stop(app)
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
                if (req.sessionGeneration != com.maodouchat.MaodouchatApp.currentSessionGeneration()) {
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

    private suspend fun createWebRtcManager(): WebRTCManager {
        // 侧载/特性模块未安装时，先从自服服务器下载 WebRTC 原生库并预加载，
        // 失败以 WebRtcNativeLoadException 抛出，由各通话路径呈现友好错误。
        WebRtcNativeLibraryLoader.ensureLoaded(getApplication())
            .onFailure { throw WebRtcNativeLoadException(it.message ?: "") }
        val iceServers = if (token.isBlank()) {
            CallIceServer.defaultStun()
        } else {
            WebRTCSignaling.fetchIceServers(token).getOrElse { CallIceServer.defaultStun() }
        }
        val stunOnly = CallIceServer.isStunOnly(iceServers)
        _uiState.update { it.copy(iceStunOnly = stunOnly) }
        return WebRTCManager(getApplication(), iceServers)
    }

    private fun configureReliabilityCallbacks(manager: WebRTCManager, session: Long) {
        fun current(): Boolean = callSessionGate.isCurrent(session) && webRTCManager === manager && !endingCall
        manager.onIceConnectionDisconnected = { if (current()) onIceConnectionChange(false) }
        manager.onIceConnectionRecovered = { if (current()) onIceConnectionChange(true) }
        manager.onIceConnectionFailed = { if (current()) onIceConnectionFailed() }
        manager.onAudioRoutesChanged = { available, selected ->
            if (current()) {
                _uiState.update {
                    it.copy(availableAudioRoutes = available, selectedAudioRoute = selected)
                }
            }
        }
        manager.onGroupPeerStateChanged = { userId, state -> if (current()) onGroupPeerStateChanged(userId, state) }
        manager.onGroupPeerVideoChanged = { userId, available ->
            if (current()) updateGroupParticipant(userId) { it.copy(videoAvailable = available) }
        }
        // 8.39：直连 SDP/信令操作失败此前无任何反馈（onOperationError 从未接线），
        // 用户干等 30s 才见「无应答」。接线后立即结束通话并给出可读错误。
        manager.onOperationError = { peerUserId, detail ->
            run {
                if (!current()) return@run
                android.util.Log.w("CallViewModel", "webrtc operation error: $detail")
                val currentContactId = _uiState.value.contactId
                if (peerUserId == null || peerUserId.isBlank() || peerUserId == currentContactId) {
                    endCall(
                        notifyPeer = false,
                        errorMessage = detail.take(200).takeIf { it.isNotBlank() }
                            ?: text(R.string.call_operation_failed)
                    )
                }
            }
        }
    }

    private fun beginCallSession(): Long {
        handledSignalingMessages.clear()
        iceRestartAttempts = 0
        return callSessionGate.begin().also { activeCallSession = it }
    }

    private fun isCurrentCallSession(session: Long, manager: WebRTCManager? = null): Boolean =
        callSessionGate.isCurrent(session) && !endingCall && (manager == null || webRTCManager === manager)

    private fun updateGroupParticipant(userId: String, transform: (GroupCallParticipantUi) -> GroupCallParticipantUi) {
        _uiState.update { state ->
            state.copy(groupParticipants = state.groupParticipants.map { participant ->
                if (participant.userId == userId) transform(participant) else participant
            })
        }
    }

    private fun loadGroupParticipantProfiles() {
        viewModelScope.launch {
            val ownerUserId = tokenManager.getUserId().orEmpty()
            if (token.isBlank() || ownerUserId.isBlank()) return@launch
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                    expectedUserId = ownerUserId,
                    liveToken = tokenManager.getToken(),
                    liveUserId = tokenManager.getUserId(),
                )
            ) {
                return@launch
            }
            val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
            ApiService.getUsers(liveToken).onSuccess { users ->
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = ownerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@onSuccess
                }
                val profiles = users.associateBy { it.id }
                _uiState.update { state ->
                    state.copy(groupParticipants = state.groupParticipants.map { participant ->
                        profiles[participant.userId]?.let { profile ->
                            participant.copy(name = profile.name, avatar = profile.avatar)
                        } ?: participant
                    })
                }
            }
        }
    }

    private fun onGroupPeerStateChanged(userId: String, state: GroupPeerConnectionState) {
        if (endingCall) return
        viewModelScope.launch {
            updateGroupParticipant(userId) { it.copy(connectionState = state) }
            when (state) {
                GroupPeerConnectionState.CONNECTED -> {
                    groupInviteTimeoutJobs.remove(userId)?.cancel()
                    groupReconnectJobs.remove(userId)?.cancel()
                    ringingTimeoutJob?.cancel()
                    _uiState.update { it.copy(callState = CallState.CONNECTED, isInitializing = false) }
                    startDurationTimer()
                    startNetworkStatsPolling()
                }
                GroupPeerConnectionState.RECONNECTING -> {
                    if (groupReconnectJobs[userId]?.isActive != true) {
                        val session = activeCallSession
                        groupReconnectJobs[userId] = viewModelScope.launch {
                            delay(CallReliabilityPolicy.ICE_RECONNECT_GRACE_MS)
                            if (
                                callSessionGate.isCurrent(session) &&
                                _uiState.value.groupParticipants.firstOrNull { it.userId == userId }?.connectionState == GroupPeerConnectionState.RECONNECTING
                            ) {
                                webRTCManager?.removeGroupPeer(userId)
                                markGroupPeerTerminal(userId, GroupPeerConnectionState.FAILED)
                            }
                        }
                    }
                }
                GroupPeerConnectionState.FAILED,
                GroupPeerConnectionState.DISCONNECTED,
                GroupPeerConnectionState.REJECTED,
                GroupPeerConnectionState.BUSY,
                GroupPeerConnectionState.NO_ANSWER -> {
                    groupInviteTimeoutJobs.remove(userId)?.cancel()
                    groupReconnectJobs.remove(userId)?.cancel()
                    if (state == GroupPeerConnectionState.FAILED || state == GroupPeerConnectionState.DISCONNECTED) {
                        webRTCManager?.removeGroupPeer(userId)
                    }
                    activeGroupMemberIds = activeGroupMemberIds - userId
                    endGroupCallIfNoActivePeers()
                }
                GroupPeerConnectionState.CONNECTING -> Unit
            }
        }
    }

    private fun markGroupPeerTerminal(userId: String, state: GroupPeerConnectionState) {
        groupInviteTimeoutJobs.remove(userId)?.cancel()
        groupReconnectJobs.remove(userId)?.cancel()
        updateGroupParticipant(userId) { it.copy(connectionState = state, videoAvailable = false) }
        activeGroupMemberIds = activeGroupMemberIds - userId
        endGroupCallIfNoActivePeers()
    }

    private fun endGroupCallIfNoActivePeers() {
        val active = _uiState.value.groupParticipants.any { GroupCallPolicy.isActive(it.connectionState) }
        if (!active && _uiState.value.isGroupCall && _uiState.value.callState != CallState.DISCONNECTED) {
            endCall(notifyPeer = false, errorMessage = text(R.string.call_group_no_active_members))
        }
    }

    private fun scheduleGroupPeerTimeout(userId: String) {
        groupInviteTimeoutJobs.remove(userId)?.cancel()
        val session = activeCallSession
        groupInviteTimeoutJobs[userId] = viewModelScope.launch {
            delay(RINGING_TIMEOUT_MS)
            if (
                callSessionGate.isCurrent(session) &&
                _uiState.value.groupParticipants.firstOrNull { it.userId == userId }?.connectionState == GroupPeerConnectionState.CONNECTING
            ) {
                webRTCManager?.removeGroupPeer(userId)
                markGroupPeerTerminal(userId, GroupPeerConnectionState.NO_ANSWER)
            }
        }
    }

    private fun startDeterministicMeshEdges(manager: WebRTCManager, primaryPeerId: String, session: Long) {
        val selfUserId = tokenManager.getUserId().orEmpty()
        activeGroupMemberIds.filter { it != primaryPeerId }.forEach { peerId ->
            scheduleGroupPeerTimeout(peerId)
            if (GroupCallPolicy.shouldInitiateMeshEdge(selfUserId, peerId) && !manager.hasGroupPeer(peerId)) {
                manager.startGroupCallToPeer(
                    peerUserId = peerId,
                    type = _uiState.value.callType,
                    onIceCandidate = { candidate ->
                        if (isCurrentCallSession(session, manager)) sendIceCandidate(peerId, candidate)
                    },
                    onOfferCreated = { sdp ->
                        if (isCurrentCallSession(session, manager)) sendSdp(peerId, "offer", sdp, groupInvite = false)
                    }
                )
            }
        }
    }

    private fun newCallId(): String = "call_${UUID.randomUUID().toString().replace("-", "")}"

    /**
     * 发起通话
     */
    fun startCall(contactId: String, contactName: String, contactAvatar: String?, callType: CallType) {
        if (_uiState.value.callState != CallState.IDLE) return
        if (!RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.CALLS)) {
            _uiState.update {
                it.copy(
                    contactId = contactId,
                    contactName = contactName,
                    contactAvatar = contactAvatar,
                    callType = callType,
                    callState = CallState.DISCONNECTED,
                    isIncoming = false,
                    isGroupCall = false,
                    isInitializing = false,
                    errorMessage = text(R.string.calls_disabled)
                )
            }
            return
        }
        val fineOk = when (callType) {
            CallType.VIDEO -> RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.VIDEO_CALL)
            CallType.AUDIO, CallType.GROUP -> RuntimeFlags.isEnabled(getApplication(), RuntimeFlags.VOICE_CALL)
        }
        if (!fineOk) {
            _uiState.update {
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
                        if (callType == CallType.VIDEO) R.string.video_call_disabled else R.string.voice_call_disabled
                    )
                )
            }
            return
        }
        if (token.isBlank()) {
            // Fail before CALLING/foreground service so the user is not left in a dead ringing UI.
            _uiState.update {
                it.copy(
                    contactId = contactId,
                    contactName = contactName,
                    contactAvatar = contactAvatar,
                    callType = callType,
                    callState = CallState.DISCONNECTED,
                    isIncoming = false,
                    isGroupCall = false,
                    isInitializing = false,
                    errorMessage = text(R.string.call_session_expired)
                )
            }
            return
        }
        endingCall = false
        val session = beginCallSession()
        activeCallId = newCallId()
        activeGroupId = ""
        meshGroupMemberIds = emptyList()
        // 8.55：呼出时快照账号，作为通话记录写入的 expectedUserId 守卫
        callLogOwnerUserId = tokenManager.getUserId().orEmpty()
        _uiState.update {
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
        startForegroundService()
        // 8.46 修复：振铃超时改到 offer 发出后启动（此前在 createWebRtcManager 之前——
        // 慢网下载 ~10MB 原生库 + 拉取 TURN 可能 >30s，超时在对端还没开始振铃时就触发挂断）
        writeCallLog(com.maodouchat.call.CallLogStore.State.MISSED)

        viewModelScope.launch {
            try {
                val manager = createWebRtcManager()
                if (!callSessionGate.isCurrent(session) || _uiState.value.callState != CallState.CALLING || _uiState.value.contactId != contactId) {
                    // 8.56：门禁失效时释放已构造的 manager，避免残留（audio 监听等）
                    runCatching { manager.release() }
                    return@launch
                }
                webRTCManager = manager
                configureReliabilityCallbacks(manager, session)
                manager.initialize()
                // 8.56：建 manager 后 flush 群成员边缓冲（发起者在初始化期间被先接听成员 offer）
                flushPendingGroupOffers(manager, session)
                manager.startCall(
                    type = callType,
                    onIceCandidate = { candidate ->
                        if (isCurrentCallSession(session, manager)) sendIceCandidate(contactId, candidate)
                    },
                    onOfferCreated = { sdp ->
                        if (!isCurrentCallSession(session, manager)) return@startCall
                        _uiState.update { it.copy(isInitializing = false) }
                        sendSdp(contactId, "offer", sdp)
                        startRingingTimeout(contactId)
                    }
                )
                observeSignaling()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: WebRtcNativeLoadException) {
                endCall(notifyPeer = false, errorMessage = text(R.string.call_webrtc_download_failed, e.message.orEmpty()))
            } catch (e: Exception) {
                endCall(notifyPeer = false, errorMessage = text(R.string.call_initialization_failed, e.message ?: text(R.string.call_unknown_error)))
            }
        }
    }

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
            scheduleGroupPeerTimeout(fromUserId)
            manager.acceptGroupOffer(
                peerUserId = fromUserId,
                remoteOfferSdp = sdp,
                type = _uiState.value.callType,
                onIceCandidate = { candidate ->
                    if (isCurrentCallSession(session, manager)) sendIceCandidate(fromUserId, candidate)
                },
                onAnswerCreated = { sdp2 ->
                    if (isCurrentCallSession(session, manager)) sendSdp(fromUserId, "answer", sdp2)
                }
            )
        }
    }

    /**
     * ICE 连接状态变化：先标记 reconnecting；如宽限期内仍未恢复则触发真正的 endCall，
     * 否则自动恢复正常，UI 上给用户一个轻提示。
     */
    private fun onIceConnectionChange(recovered: Boolean) {
        if (endingCall) return
        if (recovered) {
            iceReconnectJob?.cancel()
            iceReconnectJob = null
            iceRestartAttempts = 0
            // BUG 3 fix: ICE 连接成功时才设 CONNECTED（首次连接和重连恢复都走这里）
            _uiState.update {
                it.copy(
                    networkReconnecting = false,
                    errorMessage = null,
                    callState = CallState.CONNECTED,
                    isInitializing = false,
                )
            }
            startDurationTimer()
            startNetworkStatsPolling()
            return
        }
        val current = _uiState.value
        if (current.callState != CallState.CONNECTED) return
        _uiState.update { it.copy(networkReconnecting = true) }
        if (iceReconnectJob?.isActive == true) return
        val session = activeCallSession
        iceReconnectJob = viewModelScope.launch {
            delay(CallReliabilityPolicy.ICE_RECONNECT_GRACE_MS)
            if (callSessionGate.isCurrent(session) && _uiState.value.networkReconnecting && _uiState.value.callState == CallState.CONNECTED) {
                endCall(notifyPeer = false, errorMessage = text(R.string.call_network_disconnected))
            }
        }
    }

    private fun onIceConnectionFailed() {
        if (endingCall) return
        iceReconnectJob?.cancel()
        iceReconnectJob = null
        val current = _uiState.value
        if (
            current.callState == CallState.IDLE ||
            current.callState == CallState.DISCONNECTED
        ) {
            return
        }
        when (CallReliabilityPolicy.iceReconnectAction("FAILED", iceRestartAttempts)) {
            IceReconnectAction.RESTART_ICE -> {
                iceRestartAttempts++
                _uiState.update { it.copy(networkReconnecting = true) }
                webRTCManager?.restartIce()
                iceReconnectJob = viewModelScope.launch {
                    delay(CallReliabilityPolicy.ICE_RESTART_INTERVAL_MS)
                    if (
                        callSessionGate.isCurrent(activeCallSession) &&
                        _uiState.value.networkReconnecting &&
                        _uiState.value.callState != CallState.IDLE &&
                        _uiState.value.callState != CallState.DISCONNECTED
                    ) {
                        onIceConnectionFailed()
                    }
                }
            }
            IceReconnectAction.END_NOW -> {
                endCall(notifyPeer = false, errorMessage = text(R.string.call_network_disconnected))
            }
            else -> Unit
        }
    }

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
        val current = _uiState.value
        if (current.callState != CallState.IDLE && current.callState != CallState.DISCONNECTED) {
            if (current.contactId != contactId) {
                sendSignalWithFallback(
                    contactId,
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
        pendingOfferSdp = offerSdp
        endingCall = false
        beginCallSession()
        activeCallId = callId
        // In-app ring UI owns the call — drop FCM/full-screen incoming tray so shade
        // does not keep a second "encrypted call" while CallScreen is already open.
        if (callId.isNotBlank()) {
            com.maodouchat.util.AppNotifier.cancelIncomingCall(app, callId)
        }
        val selfUserId = tokenManager.getUserId().orEmpty()
        val normalizedMembers = groupMemberIds.filter(String::isNotBlank).distinct()
        val isGroup = groupId.isNotBlank() && normalizedMembers.size in 2..GroupCallPolicy.MAX_MESH_MEMBERS && selfUserId in normalizedMembers
        activeGroupId = if (isGroup) groupId else ""
        meshGroupMemberIds = if (isGroup) normalizedMembers.sorted() else emptyList()
        activeGroupMemberIds = if (isGroup) normalizedMembers.filter { it != selfUserId }.toSet() else emptySet()
        val participants = activeGroupMemberIds.map { GroupCallParticipantUi(it) }
        _uiState.update {
            it.copy(
                contactId = contactId,
                contactName = if (isGroup) text(R.string.call_group_name, participants.size) else contactName,
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
        // 8.55：呼入时快照账号，作为通话记录写入的 expectedUserId 守卫
        callLogOwnerUserId = tokenManager.getUserId().orEmpty()
        if (isGroup) loadGroupParticipantProfiles()
        startRingingTimeout(contactId)
        observeSignaling()
    }

    private fun startRingingTimeout(contactId: String) {
        ringingTimeoutJob?.cancel()
        val session = activeCallSession
        ringingTimeoutJob = viewModelScope.launch {
            delay(RINGING_TIMEOUT_MS)
            if (
                callSessionGate.isCurrent(session) &&
                (_uiState.value.callState == CallState.CALLING || _uiState.value.callState == CallState.RINGING)
            ) {
                val st = _uiState.value
                // Incoming no-answer: list/tray missed row (idempotent with NavGraph timer
                // via stable callId REPLACE). Outgoing CALLING still only hangs up.
                if (st.isIncoming && st.callState == CallState.RINGING) {
                    try {
                        com.maodouchat.call.MissedCallRecorder.recordRingTimeout(
                            context = app,
                            signalingCallId = activeCallId,
                            fromUserId = st.contactId.ifBlank { contactId },
                            callerName = st.contactName,
                            isVideo = st.callType == CallType.VIDEO,
                            isGroup = st.isGroupCall,
                        )
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        android.util.Log.w("CallViewModel", "missed-call record on ring timeout failed", error)
                    }
                    IncomingCallCoordinator.clear()
                    // Peer already waited 30s; still send hang-up so their UI stops ringing.
                    endCall(notifyPeer = true, errorMessage = text(R.string.call_no_answer))
                } else {
                    endCall(notifyPeer = true, errorMessage = text(R.string.call_no_answer))
                }
            }
        }
    }

    /**
     * 接听来电
     */
    fun answerCall(contactId: String? = null, contactName: String? = null, contactAvatar: String? = null, callType: CallType? = null, offerSdp: String? = null) {
        val state = _uiState.value
        val targetContactId = contactId ?: state.contactId
        val targetContactName = contactName ?: state.contactName
        val targetCallType = callType ?: state.callType
        val targetOfferSdp = offerSdp ?: pendingOfferSdp
        if (targetContactId.isBlank() || targetOfferSdp.isNullOrBlank()) {
            _uiState.update { it.copy(errorMessage = text(R.string.call_incoming_incomplete)) }
            return
        }
        if (state.callState != CallState.RINGING || !state.isIncoming || state.isInitializing) return
        if (token.isBlank()) {
            endCall(notifyPeer = false, errorMessage = text(R.string.call_session_expired))
            return
        }
        // 8.39：用户已接听即取消 30s 振铃超时——否则 WebRTC 原生库首次联网下载（慢网可超 30s）
        // 期间超时触发「无应答」挂断，用户明明已接听却被直接挂断
        ringingTimeoutJob?.cancel()
        // Accepting from in-app UI must clear any leftover FCM incoming tray.
        if (activeCallId.isNotBlank()) {
            com.maodouchat.util.AppNotifier.cancelIncomingCall(app, activeCallId)
        }
        _uiState.update {
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
        startForegroundService()
        val session = activeCallSession

        viewModelScope.launch {
            try {
                val manager = createWebRtcManager()
                val latest = _uiState.value
                if (!callSessionGate.isCurrent(session) || latest.callState != CallState.RINGING || !latest.isIncoming || latest.contactId != targetContactId) {
                    // 8.56：门禁失效时释放已构造的 manager，避免残留（audio 监听等）
                    runCatching { manager.release() }
                    return@launch
                }
                webRTCManager = manager
                configureReliabilityCallbacks(manager, session)
                manager.initialize()
                if (latest.isGroupCall) {
                    scheduleGroupPeerTimeout(targetContactId)
                    // 8.56：接听后统一 flush 被缓冲的群成员边 offer（修复成员先接听导致边永久丢失）
                    flushPendingGroupOffers(manager, session)
                    manager.acceptGroupOffer(
                        peerUserId = targetContactId,
                        remoteOfferSdp = targetOfferSdp,
                        type = targetCallType,
                        onIceCandidate = { candidate ->
                            if (isCurrentCallSession(session, manager)) sendIceCandidate(targetContactId, candidate)
                        },
                        onAnswerCreated = { sdp ->
                            if (!isCurrentCallSession(session, manager)) return@acceptGroupOffer
                            ringingTimeoutJob?.cancel()
                            pendingOfferSdp = null
                            _uiState.update { it.copy(isInitializing = false) }
                            sendSdp(targetContactId, "answer", sdp)
                        }
                    )
                    startDeterministicMeshEdges(manager, targetContactId, session)
                    observeSignaling()
                    return@launch
                }
                manager.answerCall(
                    remoteOfferSdp = targetOfferSdp,
                    type = targetCallType,
                    onIceCandidate = { candidate ->
                        if (isCurrentCallSession(session, manager)) sendIceCandidate(targetContactId, candidate)
                    },
                    onAnswerCreated = { sdp ->
                        if (!isCurrentCallSession(session, manager)) return@answerCall
                        ringingTimeoutJob?.cancel()
                        pendingOfferSdp = null
                        _uiState.update { it.copy(isInitializing = false) }
                        sendSdp(targetContactId, "answer", sdp)
                    }
                )
                observeSignaling()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: WebRtcNativeLoadException) {
                endCall(notifyPeer = false, errorMessage = text(R.string.call_webrtc_download_failed, e.message.orEmpty()))
            } catch (e: Exception) {
                endCall(notifyPeer = false, errorMessage = text(R.string.call_answer_failed, e.message ?: text(R.string.call_unknown_error)))
            }
        }
    }

    fun rejectIncomingCall() {
        val contactId = _uiState.value.contactId
        val callId = activeCallId
        if (contactId.isNotBlank()) {
            sendSignalWithFallback(contactId, "reject", "", text(R.string.call_notify_reject_failed))
        }
        // User declined — never leave FCM tray ringing after reject.
        if (callId.isNotBlank()) {
            com.maodouchat.util.AppNotifier.cancelIncomingCall(app, callId)
        }
        // 8.53：主动拒接非「未接」——不写 MISSED 通话记录（对端忙/拒接由呼出侧记未接通）
        endCall(notifyPeer = false, logMissed = false)
    }

    /**
     * 挂断
     */
    fun hangUp() {
        endCall(notifyPeer = true)
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun toggleMute(muted: Boolean) { webRTCManager?.toggleMute(muted) }
    fun toggleVideo(enabled: Boolean) { webRTCManager?.toggleVideo(enabled) }
    fun switchCamera() { webRTCManager?.switchCamera() }
    fun selectAudioRoute(route: CallAudioRoute) { webRTCManager?.selectAudioRoute(route) }

    /** UI 层创建 SurfaceViewRenderer 后调用，将渲染器连接到 WebRTCManager */
    fun attachLocalRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        webRTCManager?.attachLocalRenderer(renderer)
    }
    fun attachRemoteRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        webRTCManager?.attachRemoteRenderer(renderer)
    }
    fun attachGroupRemoteRenderer(userId: String, renderer: org.webrtc.SurfaceViewRenderer) {
        webRTCManager?.attachGroupRemoteRenderer(userId, renderer)
    }
    fun detachGroupRemoteRenderer(userId: String, renderer: org.webrtc.SurfaceViewRenderer) {
        webRTCManager?.detachGroupRemoteRenderer(userId, renderer)
    }
    fun detachLocalRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        webRTCManager?.detachLocalRenderer(renderer)
    }
    fun detachRemoteRenderer(renderer: org.webrtc.SurfaceViewRenderer) {
        webRTCManager?.detachRemoteRenderer(renderer)
    }

    private fun sendSdp(toUserId: String, type: String, sdp: SessionDescription, groupInvite: Boolean = false) {
        sendSignalWithFallback(
            toUserId,
            type,
            sdp.description,
            text(R.string.call_send_sdp_failed, type.uppercase()),
            groupInvite = groupInvite
        )
    }

    private fun sendIceCandidate(toUserId: String, candidate: IceCandidate) {
        val payload = "${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}"
        sendSignalWithFallback(toUserId, "ice-candidate", payload, text(R.string.call_send_candidate_failed))
    }

    private fun sendSignalWithFallback(
        toUserId: String,
        type: String,
        payload: String,
        errorPrefix: String,
        callIdOverride: String? = null,
        groupIdOverride: String? = null,
        groupMemberIdsOverride: List<String>? = null,
        groupInvite: Boolean = false
    ) {
        val callId = callIdOverride ?: activeCallId
        val groupId = groupIdOverride ?: activeGroupId
        val groupMembers = groupMemberIdsOverride ?: meshGroupMemberIds
        val sourceSession = activeCallSession
        viewModelScope.launch {
            if (token.isBlank()) {
                if (callSessionGate.isCurrent(sourceSession)) {
                    _uiState.update { it.copy(errorMessage = text(R.string.call_session_expired)) }
                }
                return@launch
            }

            // 关键信令（offer/answer/hang-up 等）不能只信 OkHttp 本地 enqueue 成功；
            // WS 缓冲接受 ≠ 服务端处理。关键类型始终补 REST，ICE 保持 WS-first。
            val normalizedType = CallReliabilityPolicy.normalizeSignalingType(type)
            val sentByWebSocket = WebRTCSignaling.sendViaWebSocket(toUserId, type, payload, callId, groupId, groupMembers, groupInvite)
            val needRest = CallReliabilityPolicy.isCriticalSignalingType(normalizedType) || !sentByWebSocket
            if (needRest) {
                WebRTCSignaling.sendViaRest(token, toUserId, type, payload, callId, groupId, groupMembers, groupInvite).onFailure { error ->
                    if (!callSessionGate.isCurrent(sourceSession) && normalizedType != "hang-up") return@onFailure
                    val message = text(R.string.call_error_with_reason, errorPrefix, failureReason(error))
                    if (
                        normalizedType == "offer" &&
                        error is WebRTCSignaling.SignalingException &&
                        error.code == "CALL_INVITE_RATE_LIMITED"
                    ) {
                        endCall(notifyPeer = false, errorMessage = failureReason(error))
                    } else if (normalizedType != "hang-up") {
                        _uiState.update { it.copy(errorMessage = message) }
                    }
                }
            }
        }
    }

    private fun observeSignaling() {
        // 独立管理两个 job：一个死掉不影响另一个；避免"WS  collector 死了但 polling 还活着导致永远不重连"
        if (webSocketJob?.isActive != true) {
            val signalOwnerUserId = tokenManager.getUserId().orEmpty()
            webSocketJob = viewModelScope.launch {
                WebSocketClient.events.collect { event ->
                    if (
                        signalOwnerUserId.isBlank() ||
                        !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = signalOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        return@collect
                    }
                    if (event is WebSocketEvent.SignalingReceived) {
                        handleSignalingMessage(
                            event.type,
                            event.payload,
                            event.fromUserId,
                            event.callId,
                            event.groupId,
                            event.groupMemberIds,
                            event.groupInvite
                        )
                    } else if (
                        event is WebSocketEvent.ServerError &&
                        event.code == "CALL_INVITE_RATE_LIMITED" &&
                        _uiState.value.callState == CallState.CALLING
                    ) {
                        endCall(
                            notifyPeer = false,
                            errorMessage = text(R.string.call_rate_limited, event.retryAfterSeconds ?: 60)
                        )
                    }
                }
            }
        }

        if (pollingJob?.isActive != true) {
            val pollOwnerUserId = tokenManager.getUserId().orEmpty()
            pollingJob = viewModelScope.launch {
                while (_uiState.value.callState != CallState.DISCONNECTED) {
                    delay(2000)
                    if (
                        pollOwnerUserId.isBlank() ||
                        !com.maodouchat.security.BackgroundSessionGate.mayContinue(
                            expectedUserId = pollOwnerUserId,
                            liveToken = tokenManager.getToken(),
                            liveUserId = tokenManager.getUserId(),
                        )
                    ) {
                        continue
                    }
                    val liveToken = tokenManager.getToken().orEmpty().ifBlank { token }
                    if (liveToken.isBlank()) continue
                    WebRTCSignaling.fetchPending(liveToken)
                        .onSuccess { messages ->
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = pollOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
                                return@onSuccess
                            }
                            messages.forEach {
                                handleSignalingMessage(it.type, it.payload, it.fromUserId, it.callId, it.groupId, it.groupMemberIds, it.groupInvite)
                            }
                        }
                        .onFailure { error ->
                            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                                    expectedUserId = pollOwnerUserId,
                                    liveToken = tokenManager.getToken(),
                                    liveUserId = tokenManager.getUserId(),
                                )
                            ) {
                                return@onFailure
                            }
                            _uiState.update { it.copy(errorMessage = text(R.string.call_fetch_signaling_failed, failureReason(error))) }
                        }
                }
            }
        }
    }

    /** 群通话邀请：挨个向群成员发 offer（依赖 WebRTC 端已实现 group peer 池） */
    fun startGroupCall(chatId: String, memberIds: List<String>, type: CallType) {
        if (_uiState.value.callState != CallState.IDLE) return
        val selfUserId = tokenManager.getUserId().orEmpty()
        val remoteMembers = memberIds.filter { it.isNotBlank() && it != selfUserId }.distinct()
        if (remoteMembers.isEmpty()) return
        if (token.isBlank()) {
            _uiState.update {
                it.copy(
                    contactId = chatId,
                    contactName = text(R.string.chat_group_call),
                    callType = type,
                    callState = CallState.DISCONNECTED,
                    isGroupCall = true,
                    isInitializing = false,
                    errorMessage = text(R.string.call_session_expired)
                )
            }
            return
        }
        if (selfUserId.isBlank() || remoteMembers.size + 1 > GroupCallPolicy.MAX_MESH_MEMBERS) {
            _uiState.update {
                it.copy(
                    contactId = chatId,
                    contactName = text(R.string.chat_group_call),
                    callType = type,
                    callState = CallState.DISCONNECTED,
                    errorMessage = text(R.string.call_group_mesh_limit, GroupCallPolicy.MAX_MESH_MEMBERS)
                )
            }
            return
        }
        endingCall = false
        val session = beginCallSession()
        activeCallId = newCallId()
        activeGroupId = chatId
        meshGroupMemberIds = (remoteMembers + selfUserId).sorted()
        _uiState.update {
            it.copy(
                contactId = chatId,
                contactName = text(R.string.call_group_name, remoteMembers.size),
                callType = type,
                callState = CallState.CALLING,
                isIncoming = false,
                isGroupCall = true,
                isInitializing = true,
                groupParticipants = remoteMembers.map { GroupCallParticipantUi(it) },
                errorMessage = null
            )
        }
        activeGroupMemberIds = remoteMembers.toSet()
        loadGroupParticipantProfiles()
        // 8.46 修复：群呼振铃超时改到首条 offer 发出后启动（与 1:1 呼出一致，
        // 避免慢网原生库下载/ICE 拉取超过 30s 时在对端还没开始振铃就挂断）
        startForegroundService()
        viewModelScope.launch {
            try {
                val manager = createWebRtcManager()
                val state = _uiState.value
                if (!callSessionGate.isCurrent(session) || !state.isGroupCall || state.callState != CallState.CALLING || state.contactId != chatId) {
                    // 8.56：与 startCall/answerCall 一致——门禁失效时释放已构造的 manager
                    runCatching { manager.release() }
                    return@launch
                }
                webRTCManager = manager
                configureReliabilityCallbacks(manager, session)
                manager.initialize()
                var firstOfferSent = false
                activeGroupMemberIds.forEach { memberId ->
                    scheduleGroupPeerTimeout(memberId)
                    manager.startGroupCallToPeer(
                        peerUserId = memberId,
                        type = type,
                        onIceCandidate = { candidate ->
                            if (isCurrentCallSession(session, manager)) sendIceCandidate(memberId, candidate)
                        },
                        onOfferCreated = { sdp ->
                            if (isCurrentCallSession(session, manager)) {
                                sendSdp(memberId, "offer", sdp, groupInvite = true)
                                if (!firstOfferSent) {
                                    firstOfferSent = true
                                    startRingingTimeout(chatId)
                                }
                            }
                        }
                    )
                }
                observeSignaling()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: WebRtcNativeLoadException) {
                endCall(notifyPeer = false, errorMessage = text(R.string.call_webrtc_download_failed, e.message.orEmpty()))
            } catch (e: Exception) {
                endCall(notifyPeer = false, errorMessage = text(R.string.call_group_start_failed, e.message ?: text(R.string.call_unknown_error)))
            }
        }
    }

    private fun handleSignalingMessage(
        type: String,
        payload: String,
        fromUserId: String = "",
        callId: String = "",
        groupId: String = "",
        groupMemberIds: List<String> = emptyList(),
        groupInvite: Boolean = false
    ) {
        val currentState = _uiState.value
        val expectedContactId = currentState.contactId
        val normalizedType = CallReliabilityPolicy.normalizeSignalingType(type)
        // 群通话模式不限制 fromUserId（每个对端独立）
        val isGroup = currentState.isGroupCall
        if (!CallReliabilityPolicy.shouldAcceptSignal(isGroup, expectedContactId, fromUserId)) return
        if (!GroupCallPolicy.shouldAcceptMetadata(activeGroupId, groupId, meshGroupMemberIds, groupMemberIds)) {
            if (normalizedType == "offer" && fromUserId.isNotBlank()) {
                sendSignalWithFallback(
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
        if (!CallReliabilityPolicy.shouldAcceptCallId(activeCallId, callId)) {
            if (normalizedType == "offer" && fromUserId.isNotBlank()) {
                sendSignalWithFallback(
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

        // 8.39：去重 key 用完整 payload 而非 payload.hashCode()——群 mesh 候选量大（数百~上千），
        // String.hashCode 碰撞概率可达百分之几，不同 ICE 候选碰撞时后到者被静默丢弃导致连接卡死
        val messageKey = "$callId|$fromUserId|$normalizedType|$payload"
        if (!handledSignalingMessages.add(messageKey)) return
        if (handledSignalingMessages.size > 128) {
            val iterator = handledSignalingMessages.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }

        try {
            when (normalizedType) {
                "answer" -> {
                    val st = _uiState.value
                    if (st.callState == CallState.DISCONNECTED || st.callState == CallState.IDLE) return
                    ringingTimeoutJob?.cancel()
                    if (st.isGroupCall) {
                        groupInviteTimeoutJobs.remove(fromUserId)?.cancel()
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
                            markGroupPeerTerminal(fromUserId, GroupPeerConnectionState.DISCONNECTED)
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
                        markGroupPeerTerminal(fromUserId, GroupPeerConnectionState.REJECTED)
                    } else {
                        endCall(notifyPeer = false, errorMessage = text(R.string.call_peer_rejected))
                    }
                }
                "busy" -> {
                    if (currentState.isGroupCall) {
                        webRTCManager?.removeGroupPeer(fromUserId)
                        markGroupPeerTerminal(fromUserId, GroupPeerConnectionState.BUSY)
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
                        scheduleGroupPeerTimeout(fromUserId)
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
                                    if (isCurrentCallSession(session, manager)) sendIceCandidate(fromUserId, candidate)
                                },
                                onAnswerCreated = { sdp ->
                                    if (isCurrentCallSession(session, manager)) sendSdp(fromUserId, "answer", sdp)
                                }
                            )
                        }
                    } else if (fromUserId.isNotBlank() && !st.isGroupCall) {
                        sendSignalWithFallback(
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

    private fun writeCallLog(state: com.maodouchat.call.CallLogStore.State) {
        // 8.52：全量通话记录。同一 activeCallId 幂等回写（已接通话由 endCall 补时长）。
        val st = _uiState.value
        val callId = activeCallId
        val peerId = st.contactId
        if (callId.isBlank() || peerId.isBlank()) return
        val existing = com.maodouchat.call.CallLogStore.list(app, peerId).firstOrNull { it.id == callId }
        // 呼出占位(MISSED)在接通瞬间被重写为 ANSWERED：startedAt 重置为接通时刻，
        // 使 duration = 纯通话时长（不含响铃）；呼入无占位，首次写入即 now，行为一致
        val startedAt = when {
            state == com.maodouchat.call.CallLogStore.State.ANSWERED && existing?.state == com.maodouchat.call.CallLogStore.State.MISSED ->
                System.currentTimeMillis()
            existing != null -> existing.startedAt
            else -> System.currentTimeMillis()
        }
        val durationMs = if (state == com.maodouchat.call.CallLogStore.State.ANSWERED && existing?.state == com.maodouchat.call.CallLogStore.State.ANSWERED) {
            (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        } else {
            existing?.durationMs ?: 0L
        }
        com.maodouchat.call.CallLogStore.upsert(
            app,
            com.maodouchat.call.CallLogStore.CallLogEntry(
                id = callId,
                peerId = peerId,
                peerName = st.contactName.ifBlank { peerId },
                isVideo = st.callType == CallType.VIDEO,
                direction = if (st.isIncoming) com.maodouchat.call.CallLogStore.Direction.INCOMING else com.maodouchat.call.CallLogStore.Direction.OUTGOING,
                state = state,
                startedAt = startedAt,
                durationMs = durationMs,
                isGroup = st.isGroupCall
            ),
            // 8.53：expectedUserId 守卫——8.55 改用通话开始时快照的 callLogOwnerUserId，
            // 通话中异地登出换号后旧通话不会写进新账号 key
            expectedUserId = callLogOwnerUserId.takeIf { it.isNotBlank() }
        )
    }

    private fun endCall(notifyPeer: Boolean, errorMessage: String? = null, logMissed: Boolean = true) {
        if (endingCall) return
        endingCall = true
        callSessionGate.invalidate(activeCallSession)
        val contactId = _uiState.value.contactId
        durationJob?.cancel()
        pollingJob?.cancel()
        webSocketJob?.cancel()
        ringingTimeoutJob?.cancel()
        networkStatsJob?.cancel()
        iceReconnectJob?.cancel()
        iceRefreshJob?.cancel()
        groupReconnectJobs.values.forEach(Job::cancel)
        groupReconnectJobs.clear()
        groupInviteTimeoutJobs.values.forEach(Job::cancel)
        groupInviteTimeoutJobs.clear()
        durationJob = null
        pollingJob = null
        webSocketJob = null
        ringingTimeoutJob = null
        networkStatsJob = null
        iceReconnectJob = null
        iceRefreshJob = null

        webRTCManager?.release()
        webRTCManager = null
        pendingOfferSdp = null
        // 8.56：清群 mesh 边 offer 缓冲——否则过期 offer 会串入下一通点对点通话（flush 误建 group peer）
        pendingGroupOffers.clear()

        // Snapshot before clearing; cancel only the *incoming* tray slot.
        // Missed-call notify id is salted separately so this cannot wipe a just-posted
        // missed entry after ring-timeout / peer-hang-up record.
        val hangupCallId = activeCallId
        if (hangupCallId.isNotBlank()) {
            com.maodouchat.util.AppNotifier.cancelIncomingCall(app, hangupCallId)
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
                sendHangUpDurable(
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
        handledSignalingMessages.clear()

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
        stopForegroundService()
    }

    /**
     * 定期读取 PeerConnection 的 RTC 统计，估算当前链路质量，结果写入 networkStats。
     * 不阻塞 UI，主观评价值 GOOD/FAIR/POOR。
     */
    private fun startNetworkStatsPolling() {
        if (networkStatsJob?.isActive == true) return
        val session = activeCallSession
        networkStatsJob = viewModelScope.launch {
            while (callSessionGate.isCurrent(session) && _uiState.value.callState == CallState.CONNECTED) {
                val stats = webRTCManager?.getConnectionStatsSnapshot()
                val mapped = when (
                    CallNetworkQualityPolicy.fromStats(
                        rttMs = stats?.rttMs,
                        packetLossPercent = stats?.packetLossPercent
                    )
                ) {
                    CallNetworkQualityPolicy.Level.GOOD -> NetworkQuality.GOOD
                    CallNetworkQualityPolicy.Level.FAIR -> NetworkQuality.FAIR
                    CallNetworkQualityPolicy.Level.POOR -> NetworkQuality.POOR
                    CallNetworkQualityPolicy.Level.UNKNOWN -> NetworkQuality.UNKNOWN
                }
                if (_uiState.value.networkStats != mapped) {
                    _uiState.update { it.copy(networkStats = mapped) }
                }
                delay(STATS_POLL_INTERVAL_MS)
            }
        }
    }

    private fun startDurationTimer() {
        if (durationJob?.isActive == true) return
        // 8.52：接通即回写通话记录为「已接」（幂等，ICE 重连恢复再次触发无副作用）
        writeCallLog(com.maodouchat.call.CallLogStore.State.ANSWERED)
        // 基于真实 elapsedRealtime 计算已连接秒数，避免 delay(1000) 累积漂移
        val connectedAtMs = SystemClock.elapsedRealtime()
        val session = activeCallSession
        durationJob = viewModelScope.launch {
            while (callSessionGate.isCurrent(session) && _uiState.value.callState == CallState.CONNECTED) {
                val elapsedSec = ((SystemClock.elapsedRealtime() - connectedAtMs) / 1000).toInt()
                val min = elapsedSec / 60
                val sec = elapsedSec % 60
                _uiState.update { it.copy(duration = "%02d:%02d".format(min, sec)) }
                delay(1000)
            }
        }
        // 8.35：TURN 短期凭据 1 小时过期后断线重连会因旧凭据失败——通话中每 30 分钟
        // 重新获取 ICE 配置并热替换（新 PeerConnection / 重建使用新凭据）
        iceRefreshJob?.cancel()
        iceRefreshJob = viewModelScope.launch {
            while (callSessionGate.isCurrent(session) && _uiState.value.callState == CallState.CONNECTED) {
                delay(ICE_REFRESH_INTERVAL_MS)
                if (!callSessionGate.isCurrent(session) || _uiState.value.callState != CallState.CONNECTED) break
                val manager = webRTCManager ?: continue
                val liveToken = tokenManager.getToken().orEmpty()
                if (liveToken.isBlank()) continue
                val fresh = WebRTCSignaling.fetchIceServers(liveToken).getOrNull() ?: continue
                manager.refreshIceServers(fresh)
                _uiState.update { it.copy(iceStunOnly = CallIceServer.isStunOnly(fresh)) }
            }
        }
    }

    /**
     * hang-up 走 applicationScope + REST 优先，避免 ViewModel 销毁后 viewModelScope 被取消导致对端一直响铃。
     */
    private fun sendHangUpDurable(
        toUserId: String,
        callId: String,
        groupId: String,
        groupMembers: List<String>
    ) {
        if (toUserId.isBlank()) return
        // Capture owner at hang-up request time: after account switch, do not hang up under new session.
        val hangUpOwnerUserId = tokenManager.getUserId().orEmpty()
        if (hangUpOwnerUserId.isBlank()) return
        com.maodouchat.MaodouchatApp.instance.applicationScope.launch {
            // 挂断必须尽量送达：进程/协程取消时仍跑 REST+WS，避免对端幽灵响铃
            withContext(kotlinx.coroutines.NonCancellable) {
                // Same owner + live token only; never hang-up under a switched account.
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = hangUpOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@withContext
                }
                // 启动时再读 token（可能刚 refresh）；hangUp 内部 ApiService 仍会 401 重试
                val authToken = tokenManager.getToken().orEmpty()
                if (authToken.isBlank()) return@withContext
                // 走 /api/signaling/hangup：存 hang-up 并 clearForCallExcluding，避免离线仍响铃
                try {
                    WebRTCSignaling.hangUp(authToken, toUserId, callId, groupId, groupMembers)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.w("CallViewModel", "durable hang-up REST failed", error)
                }
                // REST 失败或 WS 更快送达时仍尽力推一条（仍要求同一 owner）
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(
                        expectedUserId = hangUpOwnerUserId,
                        liveToken = tokenManager.getToken(),
                        liveUserId = tokenManager.getUserId(),
                    )
                ) {
                    return@withContext
                }
                try {
                    WebRTCSignaling.sendViaWebSocket(
                        toUserId, "hang-up", "", callId, groupId, groupMembers, false
                    )
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.w("CallViewModel", "durable hang-up WS failed", error)
                }
            }
        }
    }

    override fun onCleared() {
        // 仍在通话中则必须通知对端；hang-up 用 applicationScope，不依赖即将取消的 viewModelScope
        val shouldNotifyPeer = _uiState.value.callState != CallState.IDLE &&
            _uiState.value.callState != CallState.DISCONNECTED
        durationJob?.cancel()
        pollingJob?.cancel()
        webSocketJob?.cancel()
        ringingTimeoutJob?.cancel()
        networkStatsJob?.cancel()
        iceReconnectJob?.cancel()
        // 8.46 修复：无条件停止前台服务——若已有挂断在途（endingCall=true），endCall 开头
        // 直接 return，末尾的 stopForegroundService() 被跳过，通话前台通知/服务残留到系统回收。
        stopForegroundService()
        endCall(notifyPeer = shouldNotifyPeer)
        super.onCleared()
    }
}
