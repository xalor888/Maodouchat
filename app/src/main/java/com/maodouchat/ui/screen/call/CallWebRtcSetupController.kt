package com.maodouchat.ui.screen.call

import android.app.Application
import com.maodouchat.R
import com.maodouchat.call.WebRtcNativeLibraryLoader
import com.maodouchat.webrtc.CallIceServer
import com.maodouchat.webrtc.CallSessionGate
import com.maodouchat.webrtc.WebRTCManager
import com.maodouchat.webrtc.WebRTCSignaling

// WebRTC 建联一族：从 CallViewModel 纯搬移；manager 创建与可靠性回调装配都在这里。
internal class CallWebRtcSetupController(
    private val app: Application,
    private val token: () -> String,
    private val updateState: ((CallUiState) -> CallUiState) -> Unit,
    private val currentState: () -> CallUiState,
    private val endingCall: () -> Boolean,
    private val callSessionGate: CallSessionGate,
    private val webRTCManager: () -> WebRTCManager?,
    private val iceRecovery: CallIceRecovery,
    private val groupMesh: CallGroupMesh,
    private val endCall: (notifyPeer: Boolean, errorMessage: String?, logMissed: Boolean) -> Unit,
    private val text: (Int, Array<out Any>) -> String,
) {
    suspend fun createWebRtcManager(): WebRTCManager {
        // 侧载/特性模块未安装时，先从自服服务器下载 WebRTC 原生库并预加载，
        // 失败以 WebRtcNativeLoadException 抛出，由各通话路径呈现友好错误。
        WebRtcNativeLibraryLoader.ensureLoaded(app)
            .onFailure { throw WebRtcNativeLoadException(it.message ?: "") }
        val iceServers = if (token().isBlank()) {
            CallIceServer.defaultStun()
        } else {
            WebRTCSignaling.fetchIceServers(token()).getOrElse { CallIceServer.defaultStun() }
        }
        val stunOnly = CallIceServer.isStunOnly(iceServers)
        updateState { it.copy(iceStunOnly = stunOnly) }
        return WebRTCManager(app, iceServers)
    }

    fun configureReliabilityCallbacks(manager: WebRTCManager, session: Long) {
        fun current(): Boolean = callSessionGate.isCurrent(session) && webRTCManager() === manager && !endingCall()
        manager.onIceConnectionDisconnected = { if (current()) iceRecovery.onIceConnectionChange(false) }
        manager.onIceConnectionRecovered = { if (current()) iceRecovery.onIceConnectionChange(true) }
        manager.onIceConnectionFailed = { if (current()) iceRecovery.onIceConnectionFailed() }
        manager.onAudioRoutesChanged = { available, selected ->
            if (current()) {
                updateState {
                    it.copy(availableAudioRoutes = available, selectedAudioRoute = selected)
                }
            }
        }
        manager.onGroupPeerStateChanged = { userId, state -> if (current()) groupMesh.onGroupPeerStateChanged(userId, state) }
        manager.onGroupPeerVideoChanged = { userId, available ->
            if (current()) groupMesh.updateGroupParticipant(userId) { it.copy(videoAvailable = available) }
        }
        // 8.39：直连 SDP/信令操作失败此前无任何反馈（onOperationError 从未接线），
        // 用户干等 30s 才见「无应答」。接线后立即结束通话并给出可读错误。
        manager.onOperationError = { peerUserId, detail ->
            run {
                if (!current()) return@run
                android.util.Log.w("CallViewModel", "webrtc operation error: $detail")
                val currentContactId = currentState().contactId
                if (peerUserId == null || peerUserId.isBlank() || peerUserId == currentContactId) {
                    endCall(
                        false,
                        detail.take(200).takeIf { it.isNotBlank() }
                            ?: text(R.string.call_operation_failed, emptyArray()),
                        true
                    )
                }
            }
        }
    }
}
