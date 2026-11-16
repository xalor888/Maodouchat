package com.maodouchat.domain.messaging

// AI 任务提醒 Worker 失败重试阈值：前 3 次尝试失败重试，之后判失败。
// 与通知重排上限（MAX_NOTIFY_RETRIES）是两条规则，不共用。
object AiTaskReminderRetryPolicy {

    const val MAX_ATTEMPTS = 3

    fun shouldRetry(runAttemptCount: Int): Boolean = runAttemptCount < MAX_ATTEMPTS
}
