package com.maodouchat.data.repository

import com.maodouchat.security.BackgroundSessionGate

/**
 * Whether a mid-batch outbox item may still encrypt/send under [batchUserId].
 * Abort when logout clears tokens or account switch changes the local user id.
 */
object TextOutboxSessionPolicy {
    fun mayContinueFlush(
        batchUserId: String,
        liveToken: String?,
        liveUserId: String?,
    ): Boolean = BackgroundSessionGate.mayContinue(batchUserId, liveToken, liveUserId)
}
