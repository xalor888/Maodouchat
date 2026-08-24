package com.maodouchat.network

/**
 * Brief WS drops (NAT, heartbeat cancel, token refresh) must not flash
 * "实时连接暂时不可用" on every flap. Show the banner only after the
 * disconnect has lasted [BANNER_DELAY_MS].
 */
object RealtimeDisconnectPolicy {
    const val BANNER_DELAY_MS: Long = 2_500L
}
