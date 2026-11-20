package com.maodouchat.domain.messaging

/** 中途出箱加密/发送的会话门禁，登出清令牌或换号即中止。 */
object TextOutboxSessionPolicy {
    fun mayContinueFlush(
        batchUserId: String,
        liveToken: String?,
        liveUserId: String?,
        purgeInProgress: Boolean,
    ): Boolean = BackgroundSessionGatePolicy.mayContinue(batchUserId, liveToken, liveUserId, purgeInProgress)
}
