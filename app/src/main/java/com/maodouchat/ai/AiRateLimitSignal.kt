package com.maodouchat.ai

// HTTP 失败分类簇：从状态码 / 服务端 code / 文案判断限流或配额，生成限流信号。
internal object AiRateLimitSignal {

    /**
     * 从 HTTP 状态 / 服务端 code / 文案判断限流或配额。
     * [serverRetryAfterSeconds] 优先；否则由调用方用本地策略补秒数。
     */
    fun classifyHttpFailure(
        statusCode: Int?,
        serverCode: String?,
        serverMessage: String?,
        serverRetryAfterSeconds: Long? = null
    ): AiCostVisibilityPolicy.RateLimitSignal {
        val code = serverCode?.trim().orEmpty()
        val message = serverMessage?.trim().orEmpty()
        val blob = "$code $message".uppercase()
        val isQuota = statusCode == 402 ||
            blob.contains("QUOTA") ||
            blob.contains("BUDGET") ||
            blob.contains("PAYMENT_REQUIRED") ||
            blob.contains("BILLING") ||
            (blob.contains("INSUFFICIENT") && (blob.contains("FUND") || blob.contains("CREDIT") || blob.contains("BALANCE")))
        val isRate = statusCode == 429 ||
            blob.contains("RATE_LIMIT") ||
            blob.contains("RATE LIMITED") ||
            blob.contains("TOO_MANY") ||
            blob.contains("TOO MANY") ||
            message.contains("过于频繁") ||
            message.contains("too many", ignoreCase = true)
        val retry = serverRetryAfterSeconds
            ?.takeIf { it > 0L }
            ?.coerceAtMost(86_400L)
        return when {
            isQuota -> AiCostVisibilityPolicy.RateLimitSignal(isRateLimited = true, isQuota = true, retryAfterSeconds = retry)
            isRate -> AiCostVisibilityPolicy.RateLimitSignal(isRateLimited = true, isQuota = false, retryAfterSeconds = retry)
            else -> AiCostVisibilityPolicy.RateLimitSignal(isRateLimited = false, isQuota = false, retryAfterSeconds = null)
        }
    }

    fun mapToErrorCode(signal: AiCostVisibilityPolicy.RateLimitSignal): String? = when {
        signal.isQuota -> AiCostVisibilityPolicy.ERROR_QUOTA
        signal.isRateLimited -> AiCostVisibilityPolicy.ERROR_RATE_LIMIT
        else -> null
    }
}
