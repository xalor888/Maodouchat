package com.maodouchat.domain.messaging


// 阅后即焚倒计时分桶；R.string 映射留在 app 侧；无期限或已过期返回 null。
sealed interface DisappearCountdownKind {
    data class Seconds(val totalSec: Int) : DisappearCountdownKind
    data class Minutes(val minutes: Int, val seconds: Int) : DisappearCountdownKind
    data class Hours(val hours: Int, val minutes: Int) : DisappearCountdownKind
}

fun disappearCountdownKind(expiresAt: Long?, nowMs: Long): DisappearCountdownKind? {
    val remaining = DisappearingMessagePolicy.remainingMs(expiresAt, nowMs)
    if (remaining < 0L) return null
    val totalSec = (remaining / 1000L).coerceAtLeast(0L)
    return when {
        totalSec < 60L -> DisappearCountdownKind.Seconds(totalSec.toInt())
        totalSec < 3600L -> DisappearCountdownKind.Minutes(
            (totalSec / 60L).toInt(),
            (totalSec % 60L).toInt(),
        )
        else -> DisappearCountdownKind.Hours(
            (totalSec / 3600L).toInt(),
            ((totalSec % 3600L) / 60L).toInt(),
        )
    }
}

// 阅后即焚倒计时刷新间隔：剩余 1 小时以上每 15 秒刷新，否则每秒刷新。
fun disappearCountdownRefreshDelayMs(remainingMs: Long): Long =
    if (remainingMs > 3_600_000L) 15_000L else 1_000L
