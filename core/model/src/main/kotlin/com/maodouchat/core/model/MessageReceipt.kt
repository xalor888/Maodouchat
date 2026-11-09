package com.maodouchat.core.model

/**
 * 消息回执的纯领域模型（送达/已读/播放三态时间戳）。
 *
 * 与 app 侧 Room 实体 MessagingV2ReceiptEntity 字段一一对应，
 * 供 MessageReceiptAggregationPolicy 在 :domain:messaging 内做单调合并；
 * 持久化仍走实体，边界映射在 app 侧调用点做。
 */
data class MessageReceipt(
    val ownerUserId: String,
    val messageId: String,
    val conversationId: String,
    val recipientUserId: String,
    val deliveredAt: Long? = null,
    val readAt: Long? = null,
    val playedAt: Long? = null,
    val updatedAt: Long,
)
