package com.maodouchat.domain.messaging

/** 转发可判定结果（M08）。 */
enum class Forwardability { ALLOWED, SECRET_CHAT_BLOCKED, TERMINAL_BLOCKED, PRIVACY_BLOCKED, PIN_LOCKED_BLOCKED }

/**
 * 转发隐私策略（纯逻辑，无 Android 依赖）。
 * 密聊、终态消息、来源隐私与 PIN 锁统一在此校验，避免 UI/通知/Widget 各自散写判断。
 */
object ForwardPolicy {
    fun evaluate(
        isSecretChat: Boolean,
        isTerminalMessage: Boolean,
        senderForbidsForward: Boolean,
        isPinLocked: Boolean = false,
    ): Forwardability = when {
        isPinLocked -> Forwardability.PIN_LOCKED_BLOCKED
        isSecretChat -> Forwardability.SECRET_CHAT_BLOCKED
        isTerminalMessage -> Forwardability.TERMINAL_BLOCKED
        senderForbidsForward -> Forwardability.PRIVACY_BLOCKED
        else -> Forwardability.ALLOWED
    }
}

/** 统一转发请求（M08）：来源、目标、留言、幂等键。 */
data class ForwardRequest(
    val sourceMessageId: String,
    val sourceConversationId: String,
    val targetConversationIds: List<String>,
    val caption: String? = null,
    val idempotencyKey: String = "",
)

data class ForwardTargetResult(
    val conversationId: String,
    val success: Boolean,
    val reason: ForwardFailureReason? = null,
)

enum class ForwardFailureReason { NOT_READY, FORBIDDEN, PERMANENT, CANCELLED, PIN_LOCKED }

/** 转发协调器（M08）：目标解析、附件复制/重加密、批量结果与部分失败。 */
interface ConversationForwardCoordinator {
    suspend fun forward(request: ForwardRequest): List<ForwardTargetResult>

    suspend fun forwardBatch(requests: List<ForwardRequest>): Map<String, List<ForwardTargetResult>> =
        requests.associate { it.sourceMessageId to forward(it) }
}
