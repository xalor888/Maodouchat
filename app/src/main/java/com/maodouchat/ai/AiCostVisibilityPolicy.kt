package com.maodouchat.ai

/**
 * AI 费用 / 限流 / 取消可感知策略（纯函数）。
 *
 * 目标：
 * - 限流与配额失败对用户可区分，并给出可等待秒数（优先服务端 retry-after）
 * - 流式取消与「结果未知」明示是否可能已计费
 * - 手动重试统一提示「可能再次计费」；安全连接失败自动重试不计费提示
 *
 * 实现已按簇拆到 AiRateLimitSignal / AiCostErrorCodeCodec / AiCostWaitPolicy /
 * AiCostBillingHint / AiCostDisplaySignal，这里只保留常量、嵌套类型与统一入口。
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

    fun classifyHttpFailure(
        statusCode: Int?,
        serverCode: String?,
        serverMessage: String?,
        serverRetryAfterSeconds: Long? = null
    ): RateLimitSignal =
        AiRateLimitSignal.classifyHttpFailure(statusCode, serverCode, serverMessage, serverRetryAfterSeconds)

    fun mapToErrorCode(signal: RateLimitSignal): String? =
        AiRateLimitSignal.mapToErrorCode(signal)

    fun waitSecondsFor(
        errorCode: String?,
        serverRetryAfterSeconds: Long? = null,
        localRemainingMs: Long? = null
    ): Long =
        AiCostWaitPolicy.waitSecondsFor(errorCode, serverRetryAfterSeconds, localRemainingMs)

    fun billingHintFor(
        errorCode: String?,
        isStreaming: Boolean = false,
        isFailed: Boolean = false,
        hasScheduledAutoRetry: Boolean = false
    ): BillingHint =
        AiCostBillingHint.billingHintFor(errorCode, isStreaming, isFailed, hasScheduledAutoRetry)

    fun displaySignalFor(
        errorCode: String?,
        isStreaming: Boolean = false,
        isFailed: Boolean = false,
        hasScheduledAutoRetry: Boolean = false,
        serverRetryAfterSeconds: Long? = null,
        localRemainingMs: Long? = null
    ): DisplaySignal =
        AiCostDisplaySignal.displaySignalFor(
            errorCode, isStreaming, isFailed, hasScheduledAutoRetry, serverRetryAfterSeconds, localRemainingMs
        )

    fun shouldWarnRetryBills(errorCode: String?): Boolean =
        AiCostBillingHint.shouldWarnRetryBills(errorCode)

    fun baseErrorCode(errorCode: String?): String =
        AiCostErrorCodeCodec.baseErrorCode(errorCode)

    fun embeddedRetryAfterSeconds(errorCode: String?): Long? =
        AiCostErrorCodeCodec.embeddedRetryAfterSeconds(errorCode)

    fun encodeErrorCode(base: String, retryAfterSeconds: Long? = null): String =
        AiCostErrorCodeCodec.encodeErrorCode(base, retryAfterSeconds)
}
