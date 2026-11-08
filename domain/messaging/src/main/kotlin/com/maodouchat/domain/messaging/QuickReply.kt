package com.maodouchat.domain.messaging

import com.maodouchat.core.model.ConversationWidgetContract

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

    // ThreadLocal SHA-256 digest: MessageDigest is not thread-safe, reuse per thread.
    private val sha256Digest: ThreadLocal<java.security.MessageDigest> =
        ThreadLocal.withInitial { java.security.MessageDigest.getInstance("SHA-256") }

    /** Trim and cap reply text at the widget contract limit. */
    fun sanitizeReplyText(raw: String?): String =
        raw?.trim().orEmpty().let {
            if (it.length > ConversationWidgetContract.MAX_REPLY_LENGTH) {
                it.take(ConversationWidgetContract.MAX_REPLY_LENGTH)
            } else {
                it
            }
        }

    /** Stable dedupe key for one account + chat + text (first 8 bytes of SHA-256 hex). */
    fun dedupeKey(ownerUserId: String, chatId: String, text: String): String {
        val digest = sha256Digest.get()
            .digest(text.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "$ownerUserId|$chatId|$digest"
    }
}

/** 快捷回复用例（M11）：Receiver/Provider 只验证输入并入队命令，不直接碰 DAO/outbox。 */
interface QuickReplyUseCase {
    suspend fun reply(request: QuickReplyRequest): Result<Unit>
}
