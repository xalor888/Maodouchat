package com.maodouchat.ui.screen.call

import com.maodouchat.call.CallActionBus
import com.maodouchat.call.WebRtcNativeLibraryLoader
import com.maodouchat.session.AppRuntime
import com.maodouchat.webrtc.CallReliabilityPolicy
import com.maodouchat.webrtc.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// VM 生命周期簇：从 CallViewModel 纯搬移——init 的原生库进度/外部挂断总线收集器、
// onCleared 的全量收尾（各 controller 停摆 + 前台服务无条件停止 + 必要时通知对端）。
// VM 只留 init 触发与 onCleared 委托。
internal class CallLifecycleController(
    private val scope: CoroutineScope,
    private val currentState: () -> CallUiState,
    private val updateState: ((CallUiState) -> CallUiState) -> Unit,
    private val activeCallId: () -> String,
    private val cancelTimers: () -> Unit,
    private val stopSignalingIngress: () -> Unit,
    private val cancelRingingTimeout: () -> Unit,
    private val cancelIceRecovery: () -> Unit,
    private val stopForegroundService: () -> Unit,
    private val endCall: (notifyPeer: Boolean) -> Unit,
) {
    fun onStart() {
        scope.launch {
            WebRtcNativeLibraryLoader.progress.collect { pct ->
                updateState { it.copy(nativeDownloadProgress = pct) }
            }
        }
        scope.launch {
            CallActionBus.hangUpRequests.collect { req ->
                // Drop hang-ups buffered before logout/account switch.
                if (req.sessionGeneration != AppRuntime.currentSessionGeneration) {
                    return@collect
                }
                if (
                    CallReliabilityPolicy.shouldAcceptHangUpAction(activeCallId(), req.callId) &&
                    currentState().callState != CallState.IDLE &&
                    currentState().callState != CallState.DISCONNECTED
                ) {
                    endCall(req.notifyPeer)
                }
            }
        }
    }

    fun onCleared() {
        // 仍在通话中则必须通知对端；hang-up 用 applicationScope，不依赖即将取消的 viewModelScope
        val shouldNotifyPeer = currentState().callState != CallState.IDLE &&
            currentState().callState != CallState.DISCONNECTED
        cancelTimers()
        stopSignalingIngress()
        cancelRingingTimeout()
        cancelIceRecovery()
        // 8.46 修复：无条件停止前台服务——若已有挂断在途（endingCall=true），endCall 开头
        // 直接 return，末尾的 foregroundService.stop() 被跳过，通话前台通知/服务残留到系统回收。
        stopForegroundService()
        endCall(shouldNotifyPeer)
    }
}
