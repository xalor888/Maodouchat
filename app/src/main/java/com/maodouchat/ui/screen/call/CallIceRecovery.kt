package com.maodouchat.ui.screen.call

import com.maodouchat.R
import com.maodouchat.call.CallSessionMachine
import com.maodouchat.webrtc.CallReliabilityPolicy
import com.maodouchat.webrtc.CallSessionGate
import com.maodouchat.webrtc.CallState
import com.maodouchat.webrtc.IceReconnectAction
import com.maodouchat.webrtc.WebRTCManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ICE 连接恢复簇（断线宽限观察 / FAILED 重启退避 / 放弃后挂断）：
 * 函数体从 `CallViewModel` 逐字搬入，VM 状态经 lambda 注入（与 `CallGroupMesh` 同一装配模式）。
 */
internal class CallIceRecovery(
    private val scope: CoroutineScope,
    private val endingCall: () -> Boolean,
    private val callSessionMachine: CallSessionMachine,
    private val activeDomainSession: () -> Long,
    private val timersController: CallSessionTimersController,
    private val activeCallSession: () -> Long,
    private val callSessionGate: CallSessionGate,
    private val updateState: ((CallUiState) -> CallUiState) -> Unit,
    private val currentState: () -> CallUiState,
    private val onIceGiveUp: (String) -> Unit,
    private val text: (Int, Array<out Any>) -> String,
    private val webRTCManager: () -> WebRTCManager?,
) {
    private var iceReconnectJob: Job? = null
    // 8.56：WebRTC 原生回调线程与主线程并发访问——volatile 保证可见性，避免读到旧值误走重连
    @Volatile
    private var iceRestartAttempts = 0

    fun resetAttempts() {
        iceRestartAttempts = 0
    }

    fun cancel() {
        iceReconnectJob?.cancel()
        iceReconnectJob = null
    }

    fun onIceConnectionChange(recovered: Boolean) {
        if (endingCall()) return
        if (recovered) {
            callSessionMachine.markConnected(activeDomainSession())
            iceReconnectJob?.cancel()
            iceReconnectJob = null
            iceRestartAttempts = 0
            // BUG 3 fix: ICE 连接成功时才设 CONNECTED（首次连接和重连恢复都走这里）
            updateState {
                it.copy(
                    networkReconnecting = false,
                    errorMessage = null,
                    callState = CallState.CONNECTED,
                    isInitializing = false,
                )
            }
            timersController.startDurationTimer()
            timersController.startNetworkStatsPolling()
            return
        }
        val current = currentState()
        if (current.callState != CallState.CONNECTED) return
        updateState { it.copy(networkReconnecting = true) }
        if (iceReconnectJob?.isActive == true) return
        val session = activeCallSession()
        iceReconnectJob = scope.launch {
            delay(CallReliabilityPolicy.ICE_RECONNECT_GRACE_MS)
            if (callSessionGate.isCurrent(session) && currentState().networkReconnecting && currentState().callState == CallState.CONNECTED) {
                onIceGiveUp(text(R.string.call_network_disconnected, emptyArray()))
            }
        }
    }

    fun onIceConnectionFailed() {
        if (endingCall()) return
        iceReconnectJob?.cancel()
        iceReconnectJob = null
        val current = currentState()
        if (
            current.callState == CallState.IDLE ||
            current.callState == CallState.DISCONNECTED
        ) {
            return
        }
        when (CallReliabilityPolicy.iceReconnectAction("FAILED", iceRestartAttempts)) {
            IceReconnectAction.RESTART_ICE -> {
                iceRestartAttempts++
                updateState { it.copy(networkReconnecting = true) }
                webRTCManager()?.restartIce()
                iceReconnectJob = scope.launch {
                    delay(CallReliabilityPolicy.ICE_RESTART_INTERVAL_MS)
                    if (
                        callSessionGate.isCurrent(activeCallSession()) &&
                        currentState().networkReconnecting &&
                        currentState().callState != CallState.IDLE &&
                        currentState().callState != CallState.DISCONNECTED
                    ) {
                        onIceConnectionFailed()
                    }
                }
            }
            IceReconnectAction.END_NOW -> {
                onIceGiveUp(text(R.string.call_network_disconnected, emptyArray()))
            }
            else -> Unit
        }
    }
}
