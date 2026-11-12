package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallReliabilityPolicy
import com.maodouchat.core.model.CallSessionGate
import com.maodouchat.core.model.CallState
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.GroupCallParticipantUi
import com.maodouchat.core.model.GroupCallPolicy
import com.maodouchat.core.model.GroupPeerConnectionState
import com.maodouchat.core.session.CallSessionMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 群成员画像（id / 展示名 / 头像）：loadGroupParticipantProfiles 经端口取回的最小形状，
// app 侧把 UserDto 映射进来，domain 不碰网络 DTO。
data class CallGroupMeshProfile(
    val id: String,
    val name: String,
    val avatar: String?,
)

/**
 * 群组通话网格状态簇（成员连接状态 / 邀请超时与重连 / 确定性 mesh 建边）：
 * 从 app 侧同名类纯搬入 :domain:calls——WebRTC 走 CallWebRtcSession 端口
 * （纯数据回调，IceCandidate / SessionDescription 组装留 app 侧适配器），
 * 会话守卫、画像加载与文案走注入端口，VM 状态经 lambda 注入。
 * 群 job 双 map（邀请超时 / 重连）由本类内部持有，VM 不再直接触碰。
 */
class CallGroupMesh(
    private val scope: CoroutineScope,
    private val token: () -> String,
    private val ownerUserId: () -> String,
    private val mayContinue: (String) -> Boolean,
    private val loadProfiles: suspend (String) -> Result<List<CallGroupMeshProfile>>,
    private val endingCall: () -> Boolean,
    private val activeCallSession: () -> Long,
    private val activeDomainSession: () -> Long,
    private val groupMemberIds: () -> Set<String>,
    private val removeGroupMemberId: (String) -> Unit,
    private val cancelRingingTimeout: () -> Unit,
    private val ringingTimeoutMs: Long,
    private val updateState: ((CallUiState) -> CallUiState) -> Unit,
    private val currentState: () -> CallUiState,
    private val callSessionMachine: CallSessionMachine,
    private val callSessionGate: CallSessionGate,
    private val timersController: CallSessionTimersController,
    private val webRtc: () -> CallWebRtcSession?,
    private val isCurrentCallSession: (Long, CallWebRtcSession?) -> Boolean,
    private val signalingSender: CallSignalingSender,
    private val noActivePeersMessage: () -> String,
    private val onNoActivePeers: (String) -> Unit,
) : CallSignalingGroupMesh {
    private val groupInviteTimeoutJobs = mutableMapOf<String, Job>()
    private val groupReconnectJobs = mutableMapOf<String, Job>()
    fun updateGroupParticipant(userId: String, transform: (GroupCallParticipantUi) -> GroupCallParticipantUi) {
        updateState { state ->
            state.copy(groupParticipants = state.groupParticipants.map { participant ->
                if (participant.userId == userId) transform(participant) else participant
            })
        }
    }

    override fun loadGroupParticipantProfiles() {
        scope.launch {
            val ownerId = ownerUserId()
            if (token().isBlank() || ownerId.isBlank()) return@launch
            if (!mayContinue(ownerId)) {
                return@launch
            }
            val liveToken = token()
            loadProfiles(liveToken).onSuccess { users ->
                if (!mayContinue(ownerId)) {
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
                                webRtc()?.removeGroupPeer(userId)
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
                        webRtc()?.removeGroupPeer(userId)
                    }
                    removeGroupMemberId(userId)
                    endGroupCallIfNoActivePeers()
                }
                GroupPeerConnectionState.CONNECTING -> Unit
            }
        }
    }

    override fun markGroupPeerTerminal(userId: String, state: GroupPeerConnectionState) {
        groupInviteTimeoutJobs.remove(userId)?.cancel()
        groupReconnectJobs.remove(userId)?.cancel()
        updateGroupParticipant(userId) { it.copy(connectionState = state, videoAvailable = false) }
        removeGroupMemberId(userId)
        endGroupCallIfNoActivePeers()
    }

    private fun endGroupCallIfNoActivePeers() {
        val active = currentState().groupParticipants.any { GroupCallPolicy.isActive(it.connectionState) }
        if (!active && currentState().isGroupCall && currentState().callState != CallState.DISCONNECTED) {
            onNoActivePeers(noActivePeersMessage())
        }
    }

    override fun cancelInviteTimeout(userId: String) {
        groupInviteTimeoutJobs.remove(userId)?.cancel()
    }

    fun cancelAllGroupJobs() {
        groupInviteTimeoutJobs.values.forEach(Job::cancel)
        groupInviteTimeoutJobs.clear()
        groupReconnectJobs.values.forEach(Job::cancel)
        groupReconnectJobs.clear()
    }

    override fun scheduleGroupPeerTimeout(userId: String) {
        groupInviteTimeoutJobs.remove(userId)?.cancel()
        val session = activeCallSession()
        groupInviteTimeoutJobs[userId] = scope.launch {
            delay(ringingTimeoutMs)
            if (
                callSessionGate.isCurrent(session) &&
                currentState().groupParticipants.firstOrNull { it.userId == userId }?.connectionState == GroupPeerConnectionState.CONNECTING
            ) {
                webRtc()?.removeGroupPeer(userId)
                markGroupPeerTerminal(userId, GroupPeerConnectionState.NO_ANSWER)
            }
        }
    }

    override fun startDeterministicMeshEdges(webRtc: CallWebRtcSession, primaryPeerId: String, session: Long) {
        val selfUserId = ownerUserId()
        groupMemberIds().filter { it != primaryPeerId }.forEach { peerId ->
            scheduleGroupPeerTimeout(peerId)
            if (GroupCallPolicy.shouldInitiateMeshEdge(selfUserId, peerId) && !webRtc.hasGroupPeer(peerId)) {
                webRtc.startGroupCallToPeer(
                    peerUserId = peerId,
                    type = currentState().callType,
                    onIceCandidate = { sdpMid, sdpMLineIndex, sdp ->
                        if (isCurrentCallSession(session, webRtc)) signalingSender.sendIceCandidate(peerId, sdpMid, sdpMLineIndex, sdp)
                    },
                    onOfferCreated = { sdp ->
                        if (isCurrentCallSession(session, webRtc)) signalingSender.sendSdp(peerId, "offer", sdp, groupInvite = false)
                    }
                )
            }
        }
    }
}
