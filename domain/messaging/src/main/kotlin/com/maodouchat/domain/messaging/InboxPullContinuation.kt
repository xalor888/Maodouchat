package com.maodouchat.domain.messaging

/** Whether the inbox pull loop fetches another page. */
enum class InboxPullContinuation {
    CONTINUE,
    STOP,
}

/**
 * Decides whether the inbox pull loop continues after a page. It stops as
 * soon as a batch fails to complete (delivery order must be preserved) or
 * the server reports no more pages; only a completed batch with more pages
 * pulls again.
 */
fun decideInboxPullContinuation(
    batchCompleted: Boolean,
    hasMore: Boolean,
): InboxPullContinuation =
    if (batchCompleted && hasMore) InboxPullContinuation.CONTINUE else InboxPullContinuation.STOP

/**
 * Decides whether the acknowledgement flush fetches another batch of
 * ack-pending ids. A short batch means the queue is drained; a full batch
 * may hide more ids behind it.
 */
fun shouldFetchMoreAcknowledgements(fetchedCount: Int, limit: Int): Boolean =
    fetchedCount >= limit
