package com.maodouchat.domain.messaging

/**
 * 发送命令（M05）：一次 intent 只生成一个 local message + 一个 outbox command。
 * [idempotencyKey] 用于去重，重复提交幂等。
 */
data class SendMessageCommand(
    val conversationId: String,
    val content: ContentPayload,
    val idempotencyKey: String,
)

/** 发送结果（M05）。 */
sealed interface SendMessageResult {
    data class Success(
        val localMessageId: String,
        val durableCommitted: Boolean,
    ) : SendMessageResult

    data class Failure(val reason: SendFailureReason) : SendMessageResult
}

enum class SendFailureReason {
    /** crypto 未就绪 / 本地会话未解析。 */
    NOT_READY,

    /** 离线（可重试）。 */
    OFFLINE,

    /** 内容校验失败。 */
    VALIDATION,

    /** 不可重试的永久失败。 */
    PERMANENT,

    /** 已取消。 */
    CANCELLED,
}

/** 会话命令门面（M05）：文本、内联消息和重试的唯一 UI 入口。 */
interface ConversationCommandFacade {
    suspend fun send(command: SendMessageCommand): SendMessageResult

    suspend fun send(conversationId: String, content: ContentPayload): SendMessageResult =
        send(SendMessageCommand(conversationId = conversationId, content = content, idempotencyKey = java.util.UUID.randomUUID().toString()))

    suspend fun retry(localMessageId: String): SendMessageResult

    suspend fun cancel(localMessageId: String): Boolean
}

/** 出站会话解析器（M05）：唯一负责本地会话 ID、首次直聊创建和 crypto readiness。 */
interface OutgoingConversationResolver {
    suspend fun resolve(peerAccountId: String): ResolvedConversation
}

data class ResolvedConversation(
    val conversationId: String,
    val isCryptoReady: Boolean,
)
