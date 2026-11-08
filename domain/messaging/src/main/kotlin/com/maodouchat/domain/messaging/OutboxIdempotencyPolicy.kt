package com.maodouchat.domain.messaging

/** 发件箱按 id 入队时的幂等判定：同 id、同会话、同 kind、同载荷即重复入队，直接返回。 */
object OutboxIdempotencyPolicy {
    fun isSameOutbox(
        existingConversationId: String,
        existingKind: String,
        existingPayload: String,
        conversationId: String,
        kind: String,
        payload: String,
    ): Boolean =
        existingConversationId == conversationId &&
            existingKind == kind &&
            existingPayload == payload
}
