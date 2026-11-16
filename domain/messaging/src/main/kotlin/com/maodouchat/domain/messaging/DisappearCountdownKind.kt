package com.maodouchat.domain.messaging

import com.maodouchat.core.model.DisappearingMessagePolicy

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
