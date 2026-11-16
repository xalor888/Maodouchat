package com.maodouchat.domain.messaging

// WorkManager 一次性作业的初始延迟：触发时间减现在，时钟回拨会让延迟
// 接近 0、Worker 醒来即重排成忙循环，所以下限 1 秒兜底。
object WorkDelayPolicy {

    const val MIN_DELAY_MS = 1_000L

    fun initialDelayUntil(triggerAtMillis: Long, nowMillis: Long): Long =
        (triggerAtMillis - nowMillis).coerceAtLeast(MIN_DELAY_MS)
}
