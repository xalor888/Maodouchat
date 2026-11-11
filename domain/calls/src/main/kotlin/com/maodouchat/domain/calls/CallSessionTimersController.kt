package com.maodouchat.domain.calls

import com.maodouchat.core.model.NetworkQuality
import com.maodouchat.core.model.CallIceServer
import com.maodouchat.core.model.CallState
import com.maodouchat.core.model.CallUiState
import com.maodouchat.core.model.CallNetworkQualityPolicy
import com.maodouchat.core.model.CallSessionGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * G371：通话期间的三个后台循环（网络质量轮询 / 时长计时 / TURN 凭据 ICE 热刷新）从
 * `CallViewModel` 抽出（纯搬移不改判断）——三个 Job 的所有权随之内聚，
 * endCall/onCleared 经 `cancelTimers()` 统一取消；WebRTC 与时钟走端口
 *（getConnectionStats / refreshIceServers / fetchIceServers / elapsedRealtimeMs）。
 */
class CallSessionTimersController(
    private val scope: CoroutineScope,
    private val currentState: () -> CallUiState,
    private val updateState: ((CallUiState) -> CallUiState) -> Unit,
    private val getConnectionStats: () -> CallConnectionStats?,
    private val refreshIceServers: (List<CallIceServer>) -> Unit,
    private val fetchIceServers: suspend (String) -> Result<List<CallIceServer>>,
    private val elapsedRealtimeMs: () -> Long,
    private val activeCallSession: () -> Long,
    private val callSessionGate: CallSessionGate,
    private val token: () -> String,
    private val onLogAnswered: () -> Unit,
) {
    private var durationJob: Job? = null
    private var networkStatsJob: Job? = null
    private var iceRefreshJob: Job? = null

    private companion object {
        const val STATS_POLL_INTERVAL_MS = 4_000L
        const val ICE_REFRESH_INTERVAL_MS = 30L * 60L * 1_000L
    }

    fun startNetworkStatsPolling() {
        if (networkStatsJob?.isActive == true) return
        val session = activeCallSession()
        networkStatsJob = scope.launch {
            while (callSessionGate.isCurrent(session) && currentState().callState == CallState.CONNECTED) {
                val stats = getConnectionStats()
                val mapped = CallNetworkQualityPolicy.fromStats(
                    rttMs = stats?.rttMs,
                    packetLossPercent = stats?.packetLossPercent
                )
                if (currentState().networkStats != mapped) {
                    updateState { it.copy(networkStats = mapped) }
                }
                delay(STATS_POLL_INTERVAL_MS)
            }
        }
    }

    fun startDurationTimer() {
        if (durationJob?.isActive == true) return
        // 8.52：接通即回写通话记录为「已接」（幂等，ICE 重连恢复再次触发无副作用）
        onLogAnswered()
        // 基于真实 elapsedRealtime 计算已连接秒数，避免 delay(1000) 累积漂移
        val connectedAtMs = elapsedRealtimeMs()
        val session = activeCallSession()
        durationJob = scope.launch {
            while (callSessionGate.isCurrent(session) && currentState().callState == CallState.CONNECTED) {
                val elapsedSec = ((elapsedRealtimeMs() - connectedAtMs) / 1000).toInt()
                val min = elapsedSec / 60
                val sec = elapsedSec % 60
                updateState { it.copy(duration = "%02d:%02d".format(min, sec)) }
                delay(1000)
            }
        }
        // 8.35：TURN 短期凭据 1 小时过期后断线重连会因旧凭据失败——通话中每 30 分钟
        // 重新获取 ICE 配置并热替换（新 PeerConnection / 重建使用新凭据）
        iceRefreshJob?.cancel()
        iceRefreshJob = scope.launch {
            while (callSessionGate.isCurrent(session) && currentState().callState == CallState.CONNECTED) {
                delay(ICE_REFRESH_INTERVAL_MS)
                if (!callSessionGate.isCurrent(session) || currentState().callState != CallState.CONNECTED) break
                val liveToken = token()
                if (liveToken.isBlank()) continue
                val fresh = fetchIceServers(liveToken).getOrNull() ?: continue
                refreshIceServers(fresh)
                updateState { it.copy(iceStunOnly = CallIceServer.isStunOnly(fresh)) }
            }
        }
    }

    /** 取消全部计时循环（endCall / onCleared 统一入口）。 */
    fun cancelTimers() {
        durationJob?.cancel()
        networkStatsJob?.cancel()
        iceRefreshJob?.cancel()
        durationJob = null
        networkStatsJob = null
        iceRefreshJob = null
    }
}

/** 网络质量轮询读到的连接统计：只含 `CallNetworkQualityPolicy.fromStats` 用的两项。 */
data class CallConnectionStats(
    val rttMs: Long?,
    val packetLossPercent: Double?,
)
