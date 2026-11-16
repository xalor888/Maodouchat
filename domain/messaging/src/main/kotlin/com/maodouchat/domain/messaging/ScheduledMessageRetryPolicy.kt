package com.maodouchat.domain.messaging

// 定时消息瞬态失败重试封顶：5 次后放弃，避免无限 retry 耗电。
// 默认指数退避 30s 起，5 次约覆盖 7~8 分钟瞬态窗口。
object ScheduledMessageRetryPolicy {

    const val MAX_TRANSIENT_RETRIES = 5

    fun shouldAbandon(runAttemptCount: Int): Boolean =
        runAttemptCount >= MAX_TRANSIENT_RETRIES
}
