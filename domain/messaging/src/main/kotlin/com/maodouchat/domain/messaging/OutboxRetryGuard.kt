package com.maodouchat.domain.messaging

/** 发件箱按 id 重试时的守卫判定。纯逻辑：不碰 DAO、不碰时钟，调用方按结果入队、重试或报错。 */
object OutboxRetryGuard {
    enum class Decision { ENQUEUE, RETRY, CONVERSATION_MISMATCH, KIND_MISMATCH }

    fun decide(
        existingConversationId: String?,
        existingKind: String?,
        conversationId: String,
    ): Decision = when {
        existingConversationId == null -> Decision.ENQUEUE
        existingConversationId != conversationId -> Decision.CONVERSATION_MISMATCH
        existingKind != KIND_DATA -> Decision.KIND_MISMATCH
        else -> Decision.RETRY
    }

    private const val KIND_DATA = "DATA"
}
