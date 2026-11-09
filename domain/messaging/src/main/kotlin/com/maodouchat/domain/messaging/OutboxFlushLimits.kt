package com.maodouchat.domain.messaging

/** 出件箱刷写的分页与兜底常量。纯约定：每轮最多处理的消息数与认领过期阈值。 */
object OutboxFlushLimits {
    const val MAX_MESSAGES_PER_RUN = 100
    const val STALE_CLAIM_MS = 2L * 60L * 1_000L
}
