package com.maodouchat.ui.screen.call

import android.app.Application
import com.maodouchat.call.CallLogStore
import com.maodouchat.call.CallMediaBridge
import com.maodouchat.call.CallSessionMachine
import com.maodouchat.notification.CallNotificationService
import com.maodouchat.telecom.MaodouchatConnectionService
import com.maodouchat.webrtc.CallSessionGate
import com.maodouchat.webrtc.CallState

// 通话拆卸簇：从 CallViewModel 纯搬移——endCall 全量收尾（job 停摆、媒体释放、
// 通知/Telecom 清理、通话记录回写、对端挂断信令、会话字段复位）。
// VM 只留同签名委托。
internal class CallTeardownController(
    private val app: Application,
    private val currentState: () -> CallUiState,
    private val updateState: ((CallUiState) -> CallUiState) -> Unit,
    private val token: () -> String,
    private val endingCall: () -> Boolean,
    private val setEndingCall: (Boolean) -> Unit,
    private val activeCallId: () -> String,
    private val setActiveCallId: (String) -> Unit,
    private val activeGroupId: () -> String,
    private val setActiveGroupId: (String) -> Unit,
    private val meshGroupMemberIds: () -> List<String>,
    private val setMeshGroupMemberIds: (List<String>) -> Unit,
    private val groupMemberIds: () -> Set<String>,
    private val setGroupMemberIds: (Set<String>) -> Unit,
    private val activeCallSession: () -> Long,
    private val setActiveCallSession: (Long) -> Unit,
    private val activeDomainSession: () -> Long,
    private val setActiveDomainSession: (Long) -> Unit,
    private val clearPendingOfferSdp: () -> Unit,
    private val clearSignalingIdempotency: () -> Unit,
    private val clearInboundSignalingCursors: () -> Unit,
    private val clearOutboundSignalingCursor: () -> Unit,
    private val callSessionGate: CallSessionGate,
    private val callSessionMachine: CallSessionMachine,
    private val timersController: CallSessionTimersController,
    private val signalingIngress: CallSignalingIngressController,
    private val ringingTimeout: CallRingingTimeout,
    private val iceRecovery: CallIceRecovery,
    private val groupMesh: CallGroupMesh,
    private val mediaBridge: CallMediaBridge,
    private val hangUpSender: CallHangUpSender,
    private val foregroundService: CallForegroundServiceController,
    private val writeCallLog: (CallLogStore.State) -> Unit,
) {
    fun endCall(notifyPeer: Boolean, errorMessage: String? = null, logMissed: Boolean = true) {
        if (endingCall()) return
        setEndingCall(true)
        callSessionMachine.beginEnding(activeDomainSession())
        callSessionGate.invalidate(activeCallSession())
        val contactId = currentState().contactId
        timersController.cancelTimers()
        signalingIngress.stop()
        ringingTimeout.cancel()
        iceRecovery.cancel()
        groupMesh.cancelAllGroupJobs()

        mediaBridge.release()
        clearPendingOfferSdp()
        signalingIngress.clearPendingGroupOffers()

        // Snapshot before clearing; cancel only the *incoming* tray slot.
        // Missed-call notify id is salted separately so this cannot wipe a just-posted
        // missed entry after ring-timeout / peer-hang-up record.
        val hangupCallId = activeCallId()
        if (hangupCallId.isNotBlank()) {
            CallNotificationService.cancelIncomingCall(app, hangupCallId)
            // 销毁系统级 Telecom Connection，避免应用内挂断后系统残留「活跃通话」(幽灵来电)
            MaodouchatConnectionService.finishConnection(hangupCallId)
        }
        // 8.52：挂断前按最终状态回写（接通→补时长；未接→保持 MISSED）
        if (logMissed) {
            writeCallLog(
                if (currentState().callState == CallState.CONNECTED)
                    CallLogStore.State.ANSWERED
                else
                    CallLogStore.State.MISSED
            )
        } else {
            // 8.53：主动拒接（logMissed=false）——仅回写已接通话时长，不落 MISSED
            if (currentState().callState == CallState.CONNECTED) {
                writeCallLog(CallLogStore.State.ANSWERED)
            }
        }

        if (notifyPeer && token().isNotBlank()) {
            // 先快照 callId/目标，再清会话字段，避免 send 协程读到空 activeCallId
            val hangupGroupId = activeGroupId()
            val hangupMembers = meshGroupMemberIds()
            val targets = if (currentState().isGroupCall) groupMemberIds() else setOf(contactId)
            targets.filter(String::isNotBlank).forEach { targetId ->
                hangUpSender.sendHangUpDurable(
                    toUserId = targetId,
                    callId = hangupCallId,
                    groupId = hangupGroupId,
                    groupMembers = hangupMembers
                )
            }
        }
        setGroupMemberIds(emptySet())
        setActiveGroupId("")
        setMeshGroupMemberIds(emptyList())
        setActiveCallId("")
        setActiveCallSession(0L)
        callSessionMachine.finish(activeDomainSession())
        setActiveDomainSession(0L)
        clearSignalingIdempotency()
        clearInboundSignalingCursors()
        clearOutboundSignalingCursor()

        updateState {
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
}
