package com.maodouchat.domain.calls

import com.maodouchat.core.model.CallReliabilityPolicy

/** ICE DISCONNECTED grace before treating the call as failed (ms). */
const val ICE_RECONNECT_GRACE_MS = 10_000L

/** ICE FAILED 后最多尝试 restartIce 重连次数；超过则结束通话。 */
const val ICE_MAX_RESTART_ATTEMPTS = 2

/** 两次 ICE restart 之间的间隔（ms），给 WebRTC 足够时间收集新候选。 */
const val ICE_RESTART_INTERVAL_MS = 5_000L

enum class IceReconnectAction {
    START_GRACE,
    CANCEL_GRACE,
    RESTART_ICE,
    END_NOW,
    IGNORE
}

/**
 * ICE state transitions for the single reconnect grace window.
 * - DISCONNECTED -> start/refresh grace (ICE restart 已在 WebRTCManager 内自动触发)
 * - CONNECTED/COMPLETED -> cancel grace + reset restart counter
 * - FAILED -> 尝试 ICE restart 重连（最多 ICE_MAX_RESTART_ATTEMPTS 次），超过才 END_NOW
 */
fun CallReliabilityPolicy.iceReconnectAction(iceState: String, restartAttempts: Int = 0): IceReconnectAction = when (iceState.uppercase()) {
    "DISCONNECTED" -> IceReconnectAction.START_GRACE
    "CONNECTED", "COMPLETED" -> IceReconnectAction.CANCEL_GRACE
    "FAILED" -> if (restartAttempts < ICE_MAX_RESTART_ATTEMPTS) IceReconnectAction.RESTART_ICE else IceReconnectAction.END_NOW
    else -> IceReconnectAction.IGNORE
}
