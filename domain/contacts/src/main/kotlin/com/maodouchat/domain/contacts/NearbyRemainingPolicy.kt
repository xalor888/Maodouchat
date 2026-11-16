package com.maodouchat.domain.contacts

// 附近的人「剩余可见时间」提示分档：未分享 / 马上过期（60 秒内）/ 剩余整分钟数（向上取整）。
sealed interface NearbyRemainingHint {
    object Disabled : NearbyRemainingHint
    object Soon : NearbyRemainingHint
    data class Minutes(val minutes: Int) : NearbyRemainingHint
}

object NearbyRemainingPolicy {

    fun nearbyRemainingHint(isSharing: Boolean, remainingMs: Long): NearbyRemainingHint = when {
        !isSharing -> NearbyRemainingHint.Disabled
        remainingMs < 60_000L -> NearbyRemainingHint.Soon
        else -> NearbyRemainingHint.Minutes(((remainingMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1))
    }
}
