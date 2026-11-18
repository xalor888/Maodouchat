package com.maodouchat.domain.calls

import com.maodouchat.core.model.NetworkQuality

/**
 * Maps RTT + packet loss into UI quality buckets (W3-03).
 * Null RTT or missing sample → UNKNOWN (still measuring).
 */
object CallNetworkQualityPolicy {
    fun fromStats(rttMs: Long?, packetLossPercent: Double?): NetworkQuality {
        if (rttMs == null) return NetworkQuality.UNKNOWN
        val loss = packetLossPercent
        return when {
            loss != null && rttMs <= 200 && loss <= 1.0 -> NetworkQuality.GOOD
            loss != null && rttMs <= 500 && loss <= 4.0 -> NetworkQuality.FAIR
            loss == null && rttMs <= 200 -> NetworkQuality.GOOD
            loss == null && rttMs <= 500 -> NetworkQuality.FAIR
            else -> NetworkQuality.POOR
        }
    }
}
