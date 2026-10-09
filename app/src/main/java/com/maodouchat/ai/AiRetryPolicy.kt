package com.maodouchat.ai

/**
 * AI 调用重试 / 限流策略 — 把所有 AI 入口（改写 / 智能回复 / 翻译 / 转写 / 总结 / 语义搜索 / 图片分析 / 文件分析）
 * 套用同一套退避 + 限流规则。
 *
 * 限流：每个 chatId 每 30 秒最多 1 次 "重写/智能回复/翻译" 类调用，每 60 秒最多 1 次 "图片/文件分析" 调用，
 * 全局 1 小时最多 200 次（防止异常账号刷调用）。
 *
 * 重试：只有能够证明尚未连上服务端的失败可以指数退避；超时、响应读取失败、未知结果、
 * 进程中断和配额/拒绝类错误都不允许自动重试，避免重复计费。
 *
 * 该对象没有副作用：调用方只需按结果决定是否 mark failed / 提示重试。
 *
 * 实现已按簇拆到 AiRetryThrottle / AiRetryDecision，这里只保留嵌套类型与统一入口。
 */
object AiRetryPolicy {

    enum class Category {
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

    fun canCallNow(chatId: String, category: Category): Boolean =
        AiRetryThrottle.canCallNow(chatId, category)

    fun recordCall(chatId: String, category: Category) =
        AiRetryThrottle.recordCall(chatId, category)

    fun remainingDelayMs(chatId: String, category: Category): Long =
        AiRetryThrottle.remainingDelayMs(chatId, category)

    fun decide(errorCode: String?, attempts: Int): RetryDecision =
        AiRetryDecision.decide(errorCode, attempts)

    suspend fun awaitBackoff(chatId: String, category: Category) =
        AiRetryThrottle.awaitBackoff(chatId, category)

    fun clearSession() = AiRetryThrottle.clearSession()
}
