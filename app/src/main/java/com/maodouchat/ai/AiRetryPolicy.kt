package com.maodouchat.ai

import kotlinx.coroutines.delay

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

    private val perChatLastCall = HashMap<String, Long>()
    private val globalWindow = ArrayDeque<Long>(GLOBAL_WINDOW_SIZE + 1)

    private fun nowMs(): Long = System.currentTimeMillis()

    /** 是否可以立即调用 — 返回 true 表示可以执行；false 表示必须等 [delayMs] 毫秒。 */
    @Synchronized
    fun canCallNow(chatId: String, category: Category): Boolean {
        val now = nowMs()
        val minInterval = when (category) {
            Category.LIGHT -> LIGHT_MIN_INTERVAL_MS
            Category.HEAVY -> HEAVY_MIN_INTERVAL_MS
        }
        val key = "$category:$chatId"
        perChatLastCall[key]?.let { last ->
            if (now - last < minInterval) return false
        }
        // 全局窗口：60 分钟内最多 200 次调用
        while (globalWindow.isNotEmpty() && now - globalWindow.first() > GLOBAL_WINDOW_MS) {
            globalWindow.removeFirst()
        }
        if (globalWindow.size >= GLOBAL_WINDOW_SIZE) return false
        return true
    }

    @Synchronized
    fun recordCall(chatId: String, category: Category) {
        val now = nowMs()
        if (perChatLastCall.size > MAX_TRACKED_CHAT_KEYS) {
            val cutoff = now - GLOBAL_WINDOW_MS
            perChatLastCall.entries.removeIf { it.value < cutoff }
        }
        val key = "$category:$chatId"
        perChatLastCall[key] = now
        globalWindow.addLast(now)
    }

    @Synchronized
    fun remainingDelayMs(chatId: String, category: Category): Long {
        val now = nowMs()
        val minInterval = when (category) {
            Category.LIGHT -> LIGHT_MIN_INTERVAL_MS
            Category.HEAVY -> HEAVY_MIN_INTERVAL_MS
        }
        val key = "$category:$chatId"
        val perChat = perChatLastCall[key]?.let { last ->
            (minInterval - (now - last)).coerceAtLeast(0L)
        } ?: 0L
        while (globalWindow.isNotEmpty() && now - globalWindow.first() > GLOBAL_WINDOW_MS) {
            globalWindow.removeFirst()
        }
        val global = if (globalWindow.size >= GLOBAL_WINDOW_SIZE) {
            val oldest = globalWindow.first()
            (GLOBAL_WINDOW_MS - (now - oldest)).coerceAtLeast(1L)
        } else {
            0L
        }
        return maxOf(perChat, global)
    }

    /**
     * 根据错误特征返回是否值得退避重试。
     * 这里只读取错误特征，不读数据库 — 数据库状态由调用方在 markFailed 时落地。
     */
    fun decide(errorCode: String?, attempts: Int): RetryDecision {
        val normalized = AiCostVisibilityPolicy.baseErrorCode(errorCode).ifBlank { "UNKNOWN" }
        if (attempts >= MAX_TOTAL_ATTEMPTS) {
            return RetryDecision(
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
                RetryDecision(
                    shouldRetry = false,
                    delayMs = waitMs,
                    explanation = "rate-limited; ask user to wait",
                    visibleErrorCode = AiCostVisibilityPolicy.ERROR_RATE_LIMIT
                )
            }
            normalized.contains("UNAUTHORIZED") || normalized.contains("AUTH") -> {
                RetryDecision(false, 0L, "auth required; do not auto-retry", normalized)
            }
            normalized.contains("QUOTA") ||
                normalized.contains("BUDGET") ||
                normalized.contains("INSUFFICIENT") ||
                normalized.contains("PAYMENT") ||
                normalized.contains("BILLING") -> {
                val waitMs = AiCostVisibilityPolicy.waitSecondsFor(errorCode) * 1_000L
                RetryDecision(
                    shouldRetry = false,
                    delayMs = waitMs,
                    explanation = "quota exceeded; do not auto-retry",
                    visibleErrorCode = AiCostVisibilityPolicy.ERROR_QUOTA
                )
            }
            normalized == SAFE_CONNECTION_FAILURE -> {
                val completedRetries = (attempts - 1).coerceAtLeast(0)
                val delay = AUTO_RETRY_BASE_MS * (1L shl completedRetries.coerceAtMost(5))
                RetryDecision(
                    shouldRetry = true,
                    delayMs = delay,
                    explanation = "connection was not established; safe to retry",
                    visibleErrorCode = SAFE_CONNECTION_FAILURE
                )
            }
            else -> RetryDecision(
                shouldRetry = false,
                delayMs = 0L,
                explanation = "non-transient error",
                visibleErrorCode = normalized
            )
        }
    }

    suspend fun awaitBackoff(chatId: String, category: Category) {
        val remaining = remainingDelayMs(chatId, category)
        if (remaining > 0) delay(remaining)
    }

    /** Drop process-local rate windows so logout / account switch cannot throttle the next owner. */
    @Synchronized
    fun clearSession() {
        perChatLastCall.clear()
        globalWindow.clear()
    }

    private const val LIGHT_MIN_INTERVAL_MS = 30_000L
    private const val HEAVY_MIN_INTERVAL_MS = 60_000L
    private const val GLOBAL_WINDOW_MS = 60L * 60_000L
    private const val GLOBAL_WINDOW_SIZE = 240
    private const val MAX_TRACKED_CHAT_KEYS = 2_048
    private const val SAFE_CONNECTION_FAILURE = "CONNECTION_NOT_ESTABLISHED"
    private const val MAX_TOTAL_ATTEMPTS = 3
    private const val AUTO_RETRY_BASE_MS = 800L
}
