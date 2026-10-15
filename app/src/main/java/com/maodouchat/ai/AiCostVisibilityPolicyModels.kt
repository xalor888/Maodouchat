package com.maodouchat.ai

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
