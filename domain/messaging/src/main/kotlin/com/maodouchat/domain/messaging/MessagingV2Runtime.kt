package com.maodouchat.domain.messaging

import kotlinx.coroutines.flow.Flow

/** 出站队列状态（M02）。 */
data class OutboxState(
    val pendingCount: Int,
    val retryingCount: Int,
)

/**
 * Messaging V2 运行时端口（M02）：inbox sync、pull、ACK 通过此接口注入，
 * 不由页面或功能模块自行构造（对应「Runtime 通过接口注入」）。
 */
interface MessagingV2Runtime {
    /** 触发 inbox 同步（wake → pull → 投影）。重复 wake 可合并。 */
    suspend fun syncInbox()

    /** 确认信封（幂等）。 */
    suspend fun acknowledge(envelopeId: String)

    /** 出站队列状态流（通知 UI / retry）。 */
    val outbox: Flow<OutboxState>
}
