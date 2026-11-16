package com.maodouchat.domain.messaging

// AI 操作冷却等待秒数：剩余毫秒转显示秒数，不足 1 秒按 1 秒计。
object AiOperationWaitPolicy {

    fun waitSeconds(waitMs: Long): Long = (waitMs / 1000L).coerceAtLeast(1L)
}
