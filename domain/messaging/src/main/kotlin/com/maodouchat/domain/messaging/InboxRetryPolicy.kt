package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessagingV2InboxFailurePolicy
import com.maodouchat.core.model.MessagingV2RetryPolicy

/**
 * 收件箱信封处理失败后的重试决策。纯逻辑：调用方按返回结果落库改状态，
 * 这里不碰 DAO、不碰时钟。永久错误或达到可恢复上限走死信，其余指数退避重试。
 */
object InboxRetryPolicy {
    sealed interface Decision {
        val errorCode: String

        data class DeadLetter(override val errorCode: String) : Decision
        data class Retry(override val errorCode: String, val nextAttemptAt: Long) : Decision
    }

    fun decide(error: Exception, attemptsAfterFailure: Int, now: Long): Decision {
        val errorCode = messagingErrorCode(error)
        return if (MessagingV2InboxFailurePolicy.shouldDeadLetter(errorCode, attemptsAfterFailure)) {
            Decision.DeadLetter(errorCode)
        } else {
            Decision.Retry(
                errorCode,
                MessagingV2RetryPolicy.nextAttemptAt(now, attemptsAfterFailure),
            )
        }
    }
}
