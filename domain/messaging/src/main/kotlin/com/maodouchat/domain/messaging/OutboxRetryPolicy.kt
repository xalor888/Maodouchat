package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessagingV2RetryPolicy

/**
 * 发件箱失败后的重试决策。纯逻辑：调用方按返回结果落库改状态，
 * 这里不碰 DAO、不碰时钟。状态字符串仍由 app 侧的实体常量承载，
 * 这里只说“回准备态重加密”还是“原样重发”。
 */
object OutboxRetryPolicy {
    enum class RetryTarget { RE_ENCRYPT, RESEND }

    data class Decision(
        val target: RetryTarget,
        val nextAttemptAt: Long,
        val errorCode: String,
    )

    fun decide(
        error: Exception,
        failedWhilePreparing: Boolean,
        attempts: Int,
        now: Long,
    ): Decision {
        val target =
            if (failedWhilePreparing || OutboxFailurePolicy.needsFreshEncryption(error)) {
                RetryTarget.RE_ENCRYPT
            } else {
                RetryTarget.RESEND
            }
        val nextAttemptAt =
            if (OutboxFailurePolicy.isSenderKeyCoveragePending(error.message)) {
                // 准备阶段刚排队了 Sender Key 信箱项：当前 flush 就能把它先发出去，
                // 数据消息立刻重试即可，指数退避只会无谓拖住一次本可成功的群发。
                now
            } else {
                MessagingV2RetryPolicy.nextAttemptAt(now, attempts)
            }
        return Decision(target, nextAttemptAt, messagingErrorCode(error))
    }
}
