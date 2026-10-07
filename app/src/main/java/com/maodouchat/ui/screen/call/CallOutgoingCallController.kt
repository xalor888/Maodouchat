package com.maodouchat.ui.screen.call

import android.app.Application
import com.maodouchat.R
import com.maodouchat.call.CallLogStore
import com.maodouchat.call.CallMediaBridge
import com.maodouchat.call.GroupCallCapabilities
import com.maodouchat.util.RuntimeFlags
import com.maodouchat.webrtc.CallState
import com.maodouchat.webrtc.CallType
import com.maodouchat.webrtc.WebRTCManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 呼出一族：从 CallViewModel 纯搬移；VM 留同签名委托。
internal class CallOutgoingCallController(
    private val scope: CoroutineScope,
    private val app: Application,
    private val currentState: () -> CallUiState,
    private val updateState: ((CallUiState) -> CallUiState) -> Unit,
    private val token: () -> String,
    private val text: (Int, Array<out Any>) -> String,
    private val resetForNewOutgoingCall: (peerId: String) -> Long,
    private val resetForNewGroupCall: (chatId: String, remoteMembers: List<String>, selfUserId: String) -> Long,
    private val groupMemberIds: () -> Set<String>,
    private val setGroupMemberIds: (Set<String>) -> Unit,
    private val currentOwnerUserId: () -> String,
    private val writeCallLog: (CallLogStore.State) -> Unit,
    private val webRtcSetup: CallWebRtcSetupController,
    private val mediaBridge: CallMediaBridge,
    private val groupMesh: CallGroupMesh,
    private val sessionGateIsCurrent: (Long) -> Boolean,
    private val isCurrentCallSession: (Long, WebRTCManager?) -> Boolean,
    private val flushPendingGroupOffers: (WebRTCManager, Long) -> Unit,
    private val signalingSender: CallSignalingSender,
    private val ringingTimeout: CallRingingTimeout,
    private val foregroundService: CallForegroundServiceController,
    private val observeSignaling: () -> Unit,
    private val endCall: (notifyPeer: Boolean, errorMessage: String?, logMissed: Boolean) -> Unit,
) {
    fun startCall(contactId: String, contactName: String, contactAvatar: String?, callType: CallType) {
        if (currentState().callState != CallState.IDLE) return
        if (!RuntimeFlags.isEnabled(app, RuntimeFlags.CALLS)) {
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
                    errorMessage = text(R.string.calls_disabled, emptyArray())
                )
            }
            return
        }
        val fineOk = when (callType) {
            CallType.VIDEO -> RuntimeFlags.isEnabled(app, RuntimeFlags.VIDEO_CALL)
            CallType.AUDIO, CallType.GROUP -> RuntimeFlags.isEnabled(app, RuntimeFlags.VOICE_CALL)
        }
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
                        if (callType == CallType.VIDEO) R.string.video_call_disabled else R.string.voice_call_disabled,
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
                    errorMessage = text(R.string.call_session_expired, emptyArray())
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
        writeCallLog(CallLogStore.State.MISSED)

        scope.launch {
            try {
                val manager = webRtcSetup.createWebRtcManager()
                if (!sessionGateIsCurrent(session) || currentState().callState != CallState.CALLING || currentState().contactId != contactId) {
                    // 8.56：门禁失效时释放已构造的 manager，避免残留（audio 监听等）
                    runCatching { manager.release() }
                    return@launch
                }
                mediaBridge.bind(manager)
                webRtcSetup.configureReliabilityCallbacks(manager, session)
                manager.initialize()
                // 8.56：建 manager 后 flush 群成员边缓冲（发起者在初始化期间被先接听成员 offer）
                flushPendingGroupOffers(manager, session)
                manager.startCall(
                    type = callType,
                    onIceCandidate = { candidate ->
                        if (isCurrentCallSession(session, manager)) signalingSender.sendIceCandidate(contactId, candidate)
                    },
                    onOfferCreated = { sdp ->
                        if (!isCurrentCallSession(session, manager)) return@startCall
                        updateState { it.copy(isInitializing = false) }
                        signalingSender.sendSdp(contactId, "offer", sdp)
                        ringingTimeout.start(contactId)
                    }
                )
                observeSignaling()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: WebRtcNativeLoadException) {
                endCall(false, text(R.string.call_webrtc_download_failed, arrayOf(e.message.orEmpty())), true)
            } catch (e: Exception) {
                endCall(false, text(R.string.call_initialization_failed, arrayOf(e.message ?: text(R.string.call_unknown_error, emptyArray()))), true)
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
                    contactName = text(R.string.chat_group_call, emptyArray()),
                    callType = type,
                    callState = CallState.DISCONNECTED,
                    isGroupCall = true,
                    isInitializing = false,
                    errorMessage = text(R.string.call_session_expired, emptyArray())
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
                    contactName = text(R.string.chat_group_call, emptyArray()),
                    callType = type,
                    callState = CallState.DISCONNECTED,
                    errorMessage = text(R.string.call_group_mesh_limit, arrayOf(GroupCallCapabilities.MAX_MESH_MEMBERS))
                )
            }
            return
        }
        val session = resetForNewGroupCall(chatId, remoteMembers, selfUserId)
        updateState {
            it.copy(
                contactId = chatId,
                contactName = text(R.string.call_group_name, arrayOf(remoteMembers.size)),
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
                val manager = webRtcSetup.createWebRtcManager()
                val state = currentState()
                if (!sessionGateIsCurrent(session) || !state.isGroupCall || state.callState != CallState.CALLING || state.contactId != chatId) {
                    // 8.56：与 startCall/answerCall 一致——门禁失效时释放已构造的 manager
                    runCatching { manager.release() }
                    return@launch
                }
                mediaBridge.bind(manager)
                webRtcSetup.configureReliabilityCallbacks(manager, session)
                manager.initialize()
                var firstOfferSent = false
                groupMemberIds().forEach { memberId ->
                    groupMesh.scheduleGroupPeerTimeout(memberId)
                    manager.startGroupCallToPeer(
                        peerUserId = memberId,
                        type = type,
                        onIceCandidate = { candidate ->
                            if (isCurrentCallSession(session, manager)) signalingSender.sendIceCandidate(memberId, candidate)
                        },
                        onOfferCreated = { sdp ->
                            if (isCurrentCallSession(session, manager)) {
                                signalingSender.sendSdp(memberId, "offer", sdp, groupInvite = true)
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
                endCall(false, text(R.string.call_webrtc_download_failed, arrayOf(e.message.orEmpty())), true)
            } catch (e: Exception) {
                endCall(false, text(R.string.call_group_start_failed, arrayOf(e.message ?: text(R.string.call_unknown_error, emptyArray()))), true)
            }
        }
    }
}
