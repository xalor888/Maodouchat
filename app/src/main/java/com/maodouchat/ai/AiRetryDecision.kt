package com.maodouchat.ai

// 重试决策簇：根据错误特征返回是否值得退避重试。只读取错误特征，不读数据库 —
// 数据库状态由调用方在 markFailed 时落地。
internal object AiRetryDecision {

    fun decide(errorCode: String?, attempts: Int): AiRetryPolicy.RetryDecision {
        val normalized = AiCostVisibilityPolicy.baseErrorCode(errorCode).ifBlank { "UNKNOWN" }
        if (attempts >= MAX_TOTAL_ATTEMPTS) {
            return AiRetryPolicy.RetryDecision(
                shouldRetry = false,
                delayMs = 0L,
                explanation = "reached max auto retries; user must retry",
                visibleErrorCode = normalized
            )
        }
        return when {
            normalized.startsWith("RATE_LIMITED") ||
                normalized.contains("TOO_MANY") -> {
                val waitMs = AiCostVisibilityPolicy.waitSecondsFor(errorCode) * 1_000L
                AiRetryPolicy.RetryDecision(
                    shouldRetry = false,
                    delayMs = waitMs,
                    explanation = "rate-limited; ask user to wait",
                    visibleErrorCode = AiCostVisibilityPolicy.ERROR_RATE_LIMIT
                )
            }
            normalized.contains("UNAUTHORIZED") || normalized.contains("AUTH") -> {
                AiRetryPolicy.RetryDecision(false, 0L, "auth required; do not auto-retry", normalized)
            }
            normalized.contains("QUOTA") ||
                normalized.contains("BUDGET") ||
                normalized.contains("INSUFFICIENT") ||
                normalized.contains("PAYMENT") ||
                normalized.contains("BILLING") -> {
                val waitMs = AiCostVisibilityPolicy.waitSecondsFor(errorCode) * 1_000L
                AiRetryPolicy.RetryDecision(
                    shouldRetry = false,
                    delayMs = waitMs,
                    explanation = "quota exceeded; do not auto-retry",
                    visibleErrorCode = AiCostVisibilityPolicy.ERROR_QUOTA
                )
            }
            normalized == SAFE_CONNECTION_FAILURE -> {
                val completedRetries = (attempts - 1).coerceAtLeast(0)
                val delay = AUTO_RETRY_BASE_MS * (1L shl completedRetries.coerceAtMost(5))
                AiRetryPolicy.RetryDecision(
                    shouldRetry = true,
                    delayMs = delay,
                    explanation = "connection was not established; safe to retry",
                    visibleErrorCode = SAFE_CONNECTION_FAILURE
                )
            }
            else -> AiRetryPolicy.RetryDecision(
                shouldRetry = false,
                delayMs = 0L,
                explanation = "non-transient error",
                visibleErrorCode = normalized
            )
        }
    }

    private const val SAFE_CONNECTION_FAILURE = "CONNECTION_NOT_ESTABLISHED"
    private const val MAX_TOTAL_ATTEMPTS = 3
    private const val AUTO_RETRY_BASE_MS = 800L
}
