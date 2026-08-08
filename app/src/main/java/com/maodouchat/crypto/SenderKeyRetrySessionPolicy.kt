package com.maodouchat.crypto

import com.maodouchat.security.BackgroundSessionGate

/** Mid-batch gate for SenderKey redistribution under a stable local account. */
object SenderKeyRetrySessionPolicy {
    fun mayContinueBatch(
        batchUserId: String,
        liveToken: String?,
        liveUserId: String?,
    ): Boolean = BackgroundSessionGate.mayContinue(batchUserId, liveToken, liveUserId)
}
