package com.maodouchat.ai

/**
 * AI 费用 / 限流 / 取消可感知策略（纯函数）。
 *
 * 目标：
 * - 限流与配额失败对用户可区分，并给出可等待秒数（优先服务端 retry-after）
 * - 流式取消与「结果未知」明示是否可能已计费
 * - 手动重试统一提示「可能再次计费」；安全连接失败自动重试不计费提示
 */
object AiCostVisibilityPolicy {

    const val ERROR_RATE_LIMIT = "RATE_LIMITED"
    const val ERROR_QUOTA = "QUOTA_EXCEEDED"
    const val ERROR_CANCELLED = "CANCELLED"

    /** 本地限流默认兜底秒数（服务端未给 retry-after 时） */
    const val DEFAULT_RATE_LIMIT_WAIT_SECONDS = 30L

    /** 配额类失败默认提示等待（更长，避免用户狂点） */
    const val DEFAULT_QUOTA_WAIT_SECONDS = 300L

    enum class BillingHint {
        /** 正在请求，停止不会撤销已产生的上游调用 */
        IN_FLIGHT_MAY_BILL,
        /** 结果未知：服务端可能已处理 */
        OUTCOME_UNKNOWN_MAY_BILL,
        /** 用户主动停止流式生成 */
        CANCELLED_PARTIAL_MAY_BILL,
        /** 手动重试可能再次计费 */
        RETRY_MAY_BILL_AGAIN,
        /** 限流：本次未完成，等待后重试 */
        RATE_LIMITED_WAIT,
        /** 配额/预算：需等待或降低频率 */
        QUOTA_EXCEEDED,
        /** 安全连接失败，自动重试不计额外提示 */
        SAFE_AUTO_RETRY,
        NONE
    }

    data class RateLimitSignal(
        val isRateLimited: Boolean,
        val isQuota: Boolean,
        val retryAfterSeconds: Long?
    )

    /**
     * 从 HTTP 状态 / 服务端 code / 文案判断限流或配额。
     * [serverRetryAfterSeconds] 优先；否则由调用方用本地策略补秒数。
     */
    fun classifyHttpFailure(
        statusCode: Int?,
        serverCode: String?,
        serverMessage: String?,
        serverRetryAfterSeconds: Long? = null
    ): RateLimitSignal {
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
            isQuota -> RateLimitSignal(isRateLimited = true, isQuota = true, retryAfterSeconds = retry)
            isRate -> RateLimitSignal(isRateLimited = true, isQuota = false, retryAfterSeconds = retry)
            else -> RateLimitSignal(isRateLimited = false, isQuota = false, retryAfterSeconds = null)
        }
    }

    fun mapToErrorCode(signal: RateLimitSignal): String? = when {
        signal.isQuota -> ERROR_QUOTA
        signal.isRateLimited -> ERROR_RATE_LIMIT
        else -> null
    }

    /**
     * 展示用等待秒数：优先信号里的 retry-after，否则按错误码默认。
     */
    fun waitSecondsFor(
        errorCode: String?,
        serverRetryAfterSeconds: Long? = null,
        localRemainingMs: Long? = null
    ): Long {
        serverRetryAfterSeconds?.takeIf { it > 0L }?.let { return it.coerceAtMost(86_400L) }
        embeddedRetryAfterSeconds(errorCode)?.let { return it }
        localRemainingMs?.takeIf { it > 0L }?.let {
            return ((it + 999L) / 1000L).coerceAtLeast(1L).coerceAtMost(86_400L)
        }
        return when (baseErrorCode(errorCode)) {
            ERROR_QUOTA -> DEFAULT_QUOTA_WAIT_SECONDS
            ERROR_RATE_LIMIT -> DEFAULT_RATE_LIMIT_WAIT_SECONDS
            else -> 0L
        }
    }

    fun billingHintFor(
        errorCode: String?,
        isStreaming: Boolean = false,
        isFailed: Boolean = false,
        hasScheduledAutoRetry: Boolean = false
    ): BillingHint {
        val code = baseErrorCode(errorCode)
        return when {
            isStreaming -> BillingHint.IN_FLIGHT_MAY_BILL
            code == ERROR_CANCELLED -> BillingHint.CANCELLED_PARTIAL_MAY_BILL
            code == ERROR_RATE_LIMIT || code.startsWith("RATE_LIMIT") || code.contains("TOO_MANY") ->
                BillingHint.RATE_LIMITED_WAIT
            code == ERROR_QUOTA ||
                code.contains("QUOTA") ||
                code.contains("BUDGET") ||
                code.contains("PAYMENT") ||
                code.contains("BILLING") ||
                code.contains("INSUFFICIENT") ->
                BillingHint.QUOTA_EXCEEDED
            code in setOf(
                "OUTCOME_UNKNOWN",
                "TIMEOUT",
                "UNKNOWN",
                "INTERRUPTED"
            ) -> BillingHint.OUTCOME_UNKNOWN_MAY_BILL
            hasScheduledAutoRetry -> BillingHint.SAFE_AUTO_RETRY
            isFailed && code.isNotBlank() -> BillingHint.RETRY_MAY_BILL_AGAIN
            else -> BillingHint.NONE
        }
    }

    /**
     * 给状态栏/流式条用的展示信号：错误基码 + 等待秒数 + 计费提示。
     * ChatDetail 已读 waitSecondsFor / shouldWarnRetryBills；此函数把三者绑在一起，
     * 避免配额被当成普通失败而看不见。
     */
    data class DisplaySignal(
        val errorCode: String,
        val waitSeconds: Long,
        val hint: BillingHint,
        val warnRetryBills: Boolean
    )

    fun displaySignalFor(
        errorCode: String?,
        isStreaming: Boolean = false,
        isFailed: Boolean = false,
        hasScheduledAutoRetry: Boolean = false,
        serverRetryAfterSeconds: Long? = null,
        localRemainingMs: Long? = null
    ): DisplaySignal {
        val code = baseErrorCode(errorCode).ifBlank { "" }
        val wait = waitSecondsFor(errorCode, serverRetryAfterSeconds, localRemainingMs)
        val hint = billingHintFor(errorCode, isStreaming, isFailed, hasScheduledAutoRetry)
        return DisplaySignal(
            errorCode = code,
            waitSeconds = wait,
            hint = hint,
            warnRetryBills = shouldWarnRetryBills(errorCode)
        )
    }

    /** 手动重试按钮旁是否应展示「可能再次计费」 */
    fun shouldWarnRetryBills(errorCode: String?): Boolean {
        val code = baseErrorCode(errorCode)
        if (code.isBlank() || code == "CONNECTION_NOT_ESTABLISHED") return false
        if (code == ERROR_RATE_LIMIT || code.startsWith("RATE_LIMIT")) return true
        if (code == ERROR_QUOTA ||
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
            ERROR_CANCELLED
        )
    }

    /**
     * 持久化错误码可带 `RATE_LIMITED:45` 后缀秒数；比较 / 策略只看基码。
     */
    fun baseErrorCode(errorCode: String?): String {
        val raw = (errorCode ?: "").trim()
        if (raw.isEmpty()) return ""
        val head = raw.substringBefore(':').trim().uppercase()
        return head.ifBlank { raw.uppercase() }
    }

    fun embeddedRetryAfterSeconds(errorCode: String?): Long? {
        val raw = (errorCode ?: "").trim()
        val sep = raw.indexOf(':')
        if (sep <= 0 || sep >= raw.lastIndex) return null
        return raw.substring(sep + 1).trim().toLongOrNull()?.takeIf { it > 0L }?.coerceAtMost(86_400L)
    }

    fun encodeErrorCode(base: String, retryAfterSeconds: Long? = null): String {
        val clean = baseErrorCode(base).ifBlank { base.trim().uppercase() }
        val seconds = retryAfterSeconds?.takeIf { it > 0L }?.coerceAtMost(86_400L)
        return if (seconds != null &&
            (clean == ERROR_RATE_LIMIT || clean == ERROR_QUOTA || clean.startsWith("RATE_LIMIT"))
        ) {
            "$clean:$seconds"
        } else {
            clean
        }
    }
}
