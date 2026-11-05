package com.maodouchat.core.model

/** 1.163：群聊消息已读人数（气泡下「已读 X/Y」）。 */
data class ReadCountUi(
    val read: Int,
    val total: Int
)

data class ReadReceiptUi(
    val userId: String,
    val name: String,
    val avatar: String? = null,
    val readAt: Long? = null,
    val playedAt: Long? = null,
    /** 1.65：成员是否在线（阅读详情中显示在线点）。 */
    val isOnline: Boolean = false
)

data class AiSummaryHistoryUi(
    val cacheKey: String,
    val summary: String,
    val scope: AiSummaryScope,
    val messageCount: Int,
    val createdAt: Long
)

data class AiOperationUi(
    val id: String,
    val type: String,
    val state: String,
    val attempts: Int,
    val lastErrorCode: String?,
    val nextRetryAtMs: Long? = null,
    /** 限流 / 配额时建议等待秒数（展示倒计时） */
    val retryAfterSeconds: Long? = null
)
