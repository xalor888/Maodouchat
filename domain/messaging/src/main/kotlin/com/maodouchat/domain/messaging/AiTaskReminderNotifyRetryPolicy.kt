package com.maodouchat.domain.messaging

// 任务提醒通知未展示时的重排重试：最多 3 次、每次 15 分钟后；到上限后交给 6 小时周期对账。
// 与 Worker 失败重试（AiTaskReminderRetryPolicy）是两条规则，不共用。
object AiTaskReminderNotifyRetryPolicy {

    const val MAX_NOTIFY_RETRIES = 3

    const val RETRY_DELAY_MS = 15L * 60L * 1000L

    fun shouldRetryNotify(retries: Int): Boolean = retries < MAX_NOTIFY_RETRIES

    fun nextRetryAt(nowMs: Long): Long = nowMs + RETRY_DELAY_MS
}
