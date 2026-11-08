package com.maodouchat.domain.messaging

/** Sender Key 请求入队前的参数校验。纯逻辑：返回首个缺失项，调用方按问题报错。 */
object SenderKeyRequestValidation {
    enum class Problem { SENDER_MISSING, REVISION_MISSING, MESSAGE_MISSING }

    fun validate(
        requestedSenderUserId: String,
        groupRevision: Long,
        failedMessageId: String,
    ): Problem? = when {
        requestedSenderUserId.isBlank() -> Problem.SENDER_MISSING
        groupRevision <= 0L -> Problem.REVISION_MISSING
        failedMessageId.isBlank() -> Problem.MESSAGE_MISSING
        else -> null
    }
}
