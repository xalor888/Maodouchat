package com.maodouchat.ai

// 等待秒数策略簇：展示用等待秒数，优先服务端 retry-after，其次错误码内嵌秒数、本地剩余毫秒，最后按错误码默认。
internal object AiCostWaitPolicy {

    /**
     * 展示用等待秒数：优先信号里的 retry-after，否则按错误码默认。
     */
    fun waitSecondsFor(
        errorCode: String?,
        serverRetryAfterSeconds: Long? = null,
        localRemainingMs: Long? = null
    ): Long {
        serverRetryAfterSeconds?.takeIf { it > 0L }?.let { return it.coerceAtMost(86_400L) }
        AiCostErrorCodeCodec.embeddedRetryAfterSeconds(errorCode)?.let { return it }
        localRemainingMs?.takeIf { it > 0L }?.let {
            return ((it + 999L) / 1000L).coerceAtLeast(1L).coerceAtMost(86_400L)
        }
        return when (AiCostErrorCodeCodec.baseErrorCode(errorCode)) {
            AiCostVisibilityPolicy.ERROR_QUOTA -> AiCostVisibilityPolicy.DEFAULT_QUOTA_WAIT_SECONDS
            AiCostVisibilityPolicy.ERROR_RATE_LIMIT -> AiCostVisibilityPolicy.DEFAULT_RATE_LIMIT_WAIT_SECONDS
            else -> 0L
        }
    }
}
