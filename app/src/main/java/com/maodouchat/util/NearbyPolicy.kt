package com.maodouchat.util

/**
 * 附近的人：半径档位与可见剩余时间（纯函数，可单测）。
 * 无地图；列表 + 距离 + 半径筛选。
 */
object NearbyPolicy {
    /** API 合法范围与服务端 coerceIn(0.5, 30.0) 对齐 */
    const val MIN_RADIUS_KM = 0.5
    const val MAX_RADIUS_KM = 30.0
    const val DEFAULT_RADIUS_KM = 10.0

    /** UI 快捷档位（公里）；合法范围与服务端 coerceIn(0.5, 30.0) 对齐 */
    val RADIUS_OPTIONS_KM: List<Double> = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0)

    fun normalizeRadiusKm(radiusKm: Double?): Double {
        val value = radiusKm ?: DEFAULT_RADIUS_KM
        return value.coerceIn(MIN_RADIUS_KM, MAX_RADIUS_KM)
    }

    fun nearestOptionKm(radiusKm: Double): Double {
        val normalized = normalizeRadiusKm(radiusKm)
        return RADIUS_OPTIONS_KM.minByOrNull { kotlin.math.abs(it - normalized) }
            ?: DEFAULT_RADIUS_KM
    }

    /**
     * 服务端 expiresAt 为 epoch ms；剩余毫秒，过期或无效为 0。
     */
    fun remainingVisibleMs(expiresAt: Long, nowMs: Long): Long {
        if (expiresAt <= 0L || nowMs <= 0L) return 0L
        return (expiresAt - nowMs).coerceAtLeast(0L)
    }

    fun isStillVisible(expiresAt: Long, nowMs: Long): Boolean =
        remainingVisibleMs(expiresAt, nowMs) > 0L

    /**
     * 排序：在线优先，再按距离升序，同距再按最近更新。
     */
    fun compareNearby(
        isOnlineA: Boolean,
        distanceA: Int,
        updatedAtA: Long,
        isOnlineB: Boolean,
        distanceB: Int,
        updatedAtB: Long
    ): Int {
        val onlineCmp = isOnlineB.compareTo(isOnlineA)
        if (onlineCmp != 0) return onlineCmp
        val distCmp = distanceA.compareTo(distanceB)
        if (distCmp != 0) return distCmp
        return updatedAtB.compareTo(updatedAtA)
    }
}
