package com.maodouchat.ai

// 计费提示簇：按错误码决定 UI 应展示哪种计费提示、手动重试按钮旁是否标注「可能再次计费」。
internal object AiCostBillingHint {

    fun billingHintFor(
        errorCode: String?,
        isStreaming: Boolean = false,
        isFailed: Boolean = false,
        hasScheduledAutoRetry: Boolean = false
    ): AiCostVisibilityPolicy.BillingHint {
        val code = AiCostErrorCodeCodec.baseErrorCode(errorCode)
        return when {
            isStreaming -> AiCostVisibilityPolicy.BillingHint.IN_FLIGHT_MAY_BILL
            code == AiCostVisibilityPolicy.ERROR_CANCELLED -> AiCostVisibilityPolicy.BillingHint.CANCELLED_PARTIAL_MAY_BILL
            code == AiCostVisibilityPolicy.ERROR_RATE_LIMIT ||
                code.startsWith("RATE_LIMIT") ||
                code.contains("TOO_MANY") ->
                AiCostVisibilityPolicy.BillingHint.RATE_LIMITED_WAIT
            code == AiCostVisibilityPolicy.ERROR_QUOTA ||
                code.contains("QUOTA") ||
                code.contains("BUDGET") ||
                code.contains("PAYMENT") ||
                code.contains("BILLING") ||
                code.contains("INSUFFICIENT") ->
                AiCostVisibilityPolicy.BillingHint.QUOTA_EXCEEDED
            code in setOf(
                "OUTCOME_UNKNOWN",
                "TIMEOUT",
                "UNKNOWN",
                "INTERRUPTED"
            ) -> AiCostVisibilityPolicy.BillingHint.OUTCOME_UNKNOWN_MAY_BILL
            hasScheduledAutoRetry -> AiCostVisibilityPolicy.BillingHint.SAFE_AUTO_RETRY
            isFailed && code.isNotBlank() -> AiCostVisibilityPolicy.BillingHint.RETRY_MAY_BILL_AGAIN
            else -> AiCostVisibilityPolicy.BillingHint.NONE
        }
    }

    /** 手动重试按钮旁是否应展示「可能再次计费」 */
    fun shouldWarnRetryBills(errorCode: String?): Boolean {
        val code = AiCostErrorCodeCodec.baseErrorCode(errorCode)
        if (code.isBlank() || code == "CONNECTION_NOT_ESTABLISHED") return false
        if (code == AiCostVisibilityPolicy.ERROR_RATE_LIMIT || code.startsWith("RATE_LIMIT")) return true
        if (code == AiCostVisibilityPolicy.ERROR_QUOTA ||
            code.contains("QUOTA") ||
            code.contains("BUDGET") ||
            code.contains("PAYMENT") ||
            code.contains("BILLING") ||
            code.contains("INSUFFICIENT")
        ) return true
        return code in setOf(
            "OUTCOME_UNKNOWN",
            "TIMEOUT",
            "UNKNOWN",
            "INTERRUPTED",
            "SERVER",
            "EMPTY_RESULT",
            "INVALID_RESPONSE",
            AiCostVisibilityPolicy.ERROR_CANCELLED
        )
    }
}
