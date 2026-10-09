package com.maodouchat.ai

// 展示信号簇：把错误基码 + 等待秒数 + 计费提示绑成一个信号，供状态栏 / 流式条直接展示，避免配额被当成普通失败。
internal object AiCostDisplaySignal {

    fun displaySignalFor(
        errorCode: String?,
        isStreaming: Boolean = false,
        isFailed: Boolean = false,
        hasScheduledAutoRetry: Boolean = false,
        serverRetryAfterSeconds: Long? = null,
        localRemainingMs: Long? = null
    ): AiCostVisibilityPolicy.DisplaySignal {
        val code = AiCostErrorCodeCodec.baseErrorCode(errorCode).ifBlank { "" }
        val wait = AiCostWaitPolicy.waitSecondsFor(errorCode, serverRetryAfterSeconds, localRemainingMs)
        val hint = AiCostBillingHint.billingHintFor(errorCode, isStreaming, isFailed, hasScheduledAutoRetry)
        return AiCostVisibilityPolicy.DisplaySignal(
            errorCode = code,
            waitSeconds = wait,
            hint = hint,
            warnRetryBills = AiCostBillingHint.shouldWarnRetryBills(errorCode)
        )
    }
}
