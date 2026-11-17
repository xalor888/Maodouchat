package com.maodouchat.domain.messaging

// 密聊双因素门禁的免验证窗口钳制与门禁是否打开的判定。
object SecretGateTimeoutPolicy {
    const val DEFAULT_TIMEOUT_MS = 5L * 60 * 1000
    const val MIN_TIMEOUT_MS = 10_000L
    const val MAX_TIMEOUT_MS = 24L * 60 * 60 * 1000

    fun clampTimeoutMs(timeoutMs: Long): Long =
        timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    fun isGateOpen(lastVerifiedAt: Long, nowMs: Long, timeoutMs: Long): Boolean =
        lastVerifiedAt > 0L && nowMs - lastVerifiedAt < timeoutMs
}
