package com.maodouchat.domain.messaging

/** 快捷回复请求（M11）：通知 RemoteInput 与 Widget 共用，幂等键去重。 */
data class QuickReplyRequest(
    val conversationId: String,
    val text: String,
    val idempotencyKey: String,
)

/** 快捷回复策略（纯逻辑）：输入校验与幂等。 */
object QuickReplyPolicy {
    const val MAX_TEXT_LENGTH = 4000

    fun validate(conversationId: String, text: String): Boolean =
        conversationId.isNotBlank() && text.isNotBlank() && text.length <= MAX_TEXT_LENGTH
}

/** 快捷回复用例（M11）：Receiver/Provider 只验证输入并入队命令，不直接碰 DAO/outbox。 */
interface QuickReplyUseCase {
    suspend fun reply(request: QuickReplyRequest): Result<Unit>
}
