package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallReliabilityPolicy
import com.maodouchat.core.model.CallSessionGate
import com.maodouchat.core.model.CallState
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.IceReconnectAction
import com.maodouchat.core.session.CallSessionMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ICE 连接恢复一族：从 app 侧 CallViewModel 纯搬移；VM 留同签名委托。
class CallIceRecovery(
    private val scope: CoroutineScope,
    private val endingCall: () -> Boolean,
    private val callSessionMachine: CallSessionMachine,
    private val activeDomainSession: () -> Long,
    private val startTimers: () -> Unit,
    private val activeCallSession: () -> Long,
    private val callSessionGate: CallSessionGate,
    private val updateState: ((CallUiState) -> CallUiState) -> Unit,
    private val currentState: () -> CallUiState,
    private val onIceGiveUp: (String) -> Unit,
    private val text: (CallSignalingMessage, Array<out Any>) -> String,
    private val restartIce: () -> Unit,
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
            startTimers()
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
                onIceGiveUp(text(CallSignalingMessage.NETWORK_DISCONNECTED, emptyArray()))
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
                restartIce()
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
                onIceGiveUp(text(CallSignalingMessage.NETWORK_DISCONNECTED, emptyArray()))
            }
            else -> Unit
        }
    }
}
