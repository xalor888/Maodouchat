package com.maodouchat.domain.messaging

/** 收件箱同步的分页与兜底常量。纯约定：拉取页大小、确认批大小、拉取页数上限、认领过期阈值与轮询间隔。 */
object InboxSyncLimits {
    const val PULL_LIMIT = 200
    const val ACK_LIMIT = 200
    const val MAX_PULL_PAGES = 20
    const val STALE_PROCESSING_MS = 2L * 60L * 1_000L
    const val POLL_INTERVAL_MS = 30_000L
}
