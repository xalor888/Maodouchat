package com.maodouchat.ui.screen.call

import com.maodouchat.R
import com.maodouchat.call.CallSessionMachine
import com.maodouchat.webrtc.CallReliabilityPolicy
import com.maodouchat.webrtc.CallSessionGate
import com.maodouchat.webrtc.CallState
import com.maodouchat.webrtc.GroupCallPolicy
import com.maodouchat.webrtc.GroupPeerConnectionState
import com.maodouchat.webrtc.WebRTCManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 群组通话网格状态簇（成员连接状态 / 邀请超时与重连 / 确定性 mesh 建边）：
 * 函数体从 `CallViewModel` 逐字搬入，VM 状态经 lambda 注入（与 `CallSignalingSender` 同一装配模式）。
 */
internal class CallGroupMesh(
    private val scope: CoroutineScope,
    private val token: () -> String,
    private val endingCall: () -> Boolean,
    private val activeCallSession: () -> Long,
    private val activeDomainSession: () -> Long,
    private val groupMemberIds: () -> Set<String>,
    private val removeGroupMemberId: (String) -> Unit,
    private val groupInviteTimeoutJobs: MutableMap<String, Job>,
    private val groupReconnectJobs: MutableMap<String, Job>,
    private val cancelRingingTimeout: () -> Unit,
    private val ringingTimeoutMs: Long,
    private val updateState: ((CallUiState) -> CallUiState) -> Unit,
    private val currentState: () -> CallUiState,
    private val callSessionMachine: CallSessionMachine,
    private val callSessionGate: CallSessionGate,
    private val timersController: CallSessionTimersController,
    private val webRTCManager: () -> WebRTCManager?,
    private val isCurrentCallSession: (Long, WebRTCManager?) -> Boolean,
    private val signalingSender: CallSignalingSender,
    private val text: (Int, Array<out Any>) -> String,
    private val onNoActivePeers: (String) -> Unit,
) {
    fun updateGroupParticipant(userId: String, transform: (GroupCallParticipantUi) -> GroupCallParticipantUi) {
        updateState { state ->
            state.copy(groupParticipants = state.groupParticipants.map { participant ->
                if (participant.userId == userId) transform(participant) else participant
            })
        }
    }

    fun loadGroupParticipantProfiles() {
        scope.launch {
            val ownerUserId = com.maodouchat.session.CurrentSession.ownerUserId()
            if (token().isBlank() || ownerUserId.isBlank()) return@launch
            if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId)) {
                return@launch
            }
            val liveToken = token()
            com.maodouchat.data.repository.UserNetworkRepository().users(liveToken).onSuccess { users ->
                if (!com.maodouchat.security.BackgroundSessionGate.mayContinue(expectedUserId = ownerUserId)) {
                    return@onSuccess
                }
                val profiles = users.associateBy { it.id }
                updateState { state ->
                    state.copy(groupParticipants = state.groupParticipants.map { participant ->
                        profiles[participant.userId]?.let { profile ->
                            participant.copy(name = profile.name, avatar = profile.avatar)
                        } ?: participant
                    })
                }
            }
        }
    }

    fun onGroupPeerStateChanged(userId: String, state: GroupPeerConnectionState) {
        if (endingCall()) return
        scope.launch {
            updateGroupParticipant(userId) { it.copy(connectionState = state) }
            when (state) {
                GroupPeerConnectionState.CONNECTED -> {
                    callSessionMachine.markConnected(activeDomainSession())
                    groupInviteTimeoutJobs.remove(userId)?.cancel()
                    groupReconnectJobs.remove(userId)?.cancel()
                    cancelRingingTimeout()
                    updateState { it.copy(callState = CallState.CONNECTED, isInitializing = false) }
                    timersController.startDurationTimer()
                    timersController.startNetworkStatsPolling()
                }
                GroupPeerConnectionState.RECONNECTING -> {
                    if (groupReconnectJobs[userId]?.isActive != true) {
                        val session = activeCallSession()
                        groupReconnectJobs[userId] = scope.launch {
                            delay(CallReliabilityPolicy.ICE_RECONNECT_GRACE_MS)
                            if (
                                callSessionGate.isCurrent(session) &&
                                currentState().groupParticipants.firstOrNull { it.userId == userId }?.connectionState == GroupPeerConnectionState.RECONNECTING
                            ) {
                                webRTCManager()?.removeGroupPeer(userId)
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
                        webRTCManager()?.removeGroupPeer(userId)
                    }
                    removeGroupMemberId(userId)
                    endGroupCallIfNoActivePeers()
                }
                GroupPeerConnectionState.CONNECTING -> Unit
            }
        }
    }

    fun markGroupPeerTerminal(userId: String, state: GroupPeerConnectionState) {
        groupInviteTimeoutJobs.remove(userId)?.cancel()
        groupReconnectJobs.remove(userId)?.cancel()
        updateGroupParticipant(userId) { it.copy(connectionState = state, videoAvailable = false) }
        removeGroupMemberId(userId)
        endGroupCallIfNoActivePeers()
    }

    private fun endGroupCallIfNoActivePeers() {
        val active = currentState().groupParticipants.any { GroupCallPolicy.isActive(it.connectionState) }
        if (!active && currentState().isGroupCall && currentState().callState != CallState.DISCONNECTED) {
            onNoActivePeers(text(R.string.call_group_no_active_members, emptyArray()))
        }
    }

    fun scheduleGroupPeerTimeout(userId: String) {
        groupInviteTimeoutJobs.remove(userId)?.cancel()
        val session = activeCallSession()
        groupInviteTimeoutJobs[userId] = scope.launch {
            delay(ringingTimeoutMs)
            if (
                callSessionGate.isCurrent(session) &&
                currentState().groupParticipants.firstOrNull { it.userId == userId }?.connectionState == GroupPeerConnectionState.CONNECTING
            ) {
                webRTCManager()?.removeGroupPeer(userId)
                markGroupPeerTerminal(userId, GroupPeerConnectionState.NO_ANSWER)
            }
        }
    }

    fun startDeterministicMeshEdges(manager: WebRTCManager, primaryPeerId: String, session: Long) {
        val selfUserId = com.maodouchat.session.CurrentSession.ownerUserId()
        groupMemberIds().filter { it != primaryPeerId }.forEach { peerId ->
            scheduleGroupPeerTimeout(peerId)
            if (GroupCallPolicy.shouldInitiateMeshEdge(selfUserId, peerId) && !manager.hasGroupPeer(peerId)) {
                manager.startGroupCallToPeer(
                    peerUserId = peerId,
                    type = currentState().callType,
                    onIceCandidate = { candidate ->
                        if (isCurrentCallSession(session, manager)) signalingSender.sendIceCandidate(peerId, candidate)
                    },
                    onOfferCreated = { sdp ->
                        if (isCurrentCallSession(session, manager)) signalingSender.sendSdp(peerId, "offer", sdp, groupInvite = false)
                    }
                )
            }
        }
    }
}
