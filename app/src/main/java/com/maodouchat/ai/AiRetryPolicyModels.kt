package com.maodouchat.ai

enum class AiRetryPolicyCategory {
    /** 用户高频操作：改写 / 智能回复 / 翻译 / 转写 / 总结 / 语义搜索 */
    LIGHT,
    /** 重型操作：图片理解 / 文件问答 / 群助手总结 */
    HEAVY
}

data class RetryDecision(
    val shouldRetry: Boolean,
    val delayMs: Long,
    val explanation: String,
    /** 给 UI 的错误码；限流/配额时为 RATE_LIMITED / QUOTA_EXCEEDED，避免静默失败。 */
    val visibleErrorCode: String? = null
)
