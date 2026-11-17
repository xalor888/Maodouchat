package com.maodouchat.domain.messaging

// 密聊自动销毁的会话 TTL 钳制：读/写两侧同一边界。
object SecretTtlPolicy {
    const val DEFAULT_TTL_SECONDS = 86_400L
    const val MIN_TTL_SECONDS = 300L
    const val MAX_TTL_SECONDS = 30L * 24 * 60 * 60

    fun clampTtlSeconds(seconds: Long): Long =
        seconds.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
}
