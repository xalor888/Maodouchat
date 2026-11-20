package com.maodouchat.domain.messaging

/** Mid-batch gate for SenderKey redistribution under a stable local account. */
object SenderKeyRetrySessionPolicy {
    fun mayContinueBatch(
        batchUserId: String,
        liveToken: String?,
        liveUserId: String?,
        purgeInProgress: Boolean,
    ): Boolean = BackgroundSessionGatePolicy.mayContinue(batchUserId, liveToken, liveUserId, purgeInProgress)
}
