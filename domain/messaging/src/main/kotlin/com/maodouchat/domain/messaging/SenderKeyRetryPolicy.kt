package com.maodouchat.domain.messaging

// Sender Key 覆盖重试退避：协议/服务器错与瞬态网络错分档，指数退避 + 抖动。
object SenderKeyRetryPolicy {

    // 协议/服务器错误退避：30s -> 60s -> 120s -> 240s，最多 5 次
    const val INITIAL_DELAY_MS = 30_000L
    const val MAX_DELAY_MS = 30 * 60_000L
    const val MAX_ATTEMPTS = 5
    // 网络错误退避：更短初始 + 更多重试（弱网场景），10s -> 20s -> 40s -> 80s -> 160s -> 320s，最多 8 次
    const val NETWORK_INITIAL_DELAY_MS = 10_000L
    const val NETWORK_MAX_DELAY_MS = 5 * 60_000L
    const val MAX_ATTEMPTS_NETWORK = 8

    /** 瞬态网络错误判定（弱网退避与 8.37 破坏性操作防护共用）。 */
    fun isTransientNetworkError(error: Throwable): Boolean =
        error is java.io.IOException ||
            error is java.net.SocketTimeoutException ||
            (error.message?.contains("network", ignoreCase = true) == true) ||
            (error.message?.contains("timeout", ignoreCase = true) == true) ||
            (error.message?.contains("unreachable", ignoreCase = true) == true) ||
            (error.message?.contains("canceled", ignoreCase = true) == true)

    fun maxAttempts(isNetworkError: Boolean): Int =
        if (isNetworkError) MAX_ATTEMPTS_NETWORK else MAX_ATTEMPTS

    fun shouldAbandon(attempts: Int, isNetworkError: Boolean): Boolean =
        attempts >= maxAttempts(isNetworkError)

    /**
     * 指数退避 + 抖动（±25%），避免弱网下多设备同时重试的惊群效应。
     * jitterFactor 由调用方传入 Math.random()，domain 侧不取随机数，测试可定值。
     */
    fun retryDelayMs(attempts: Int, isNetworkError: Boolean, jitterFactor: Double): Long {
        val baseDelay = if (isNetworkError) NETWORK_INITIAL_DELAY_MS else INITIAL_DELAY_MS
        val maxDelay = if (isNetworkError) NETWORK_MAX_DELAY_MS else MAX_DELAY_MS
        val expDelay = (baseDelay * (1L shl (attempts - 1))).coerceAtMost(maxDelay)
        val jitter = (expDelay * 0.25 * (jitterFactor - 0.5) * 2).toLong()
        return (expDelay + jitter).coerceAtLeast(baseDelay / 2)
    }
}
