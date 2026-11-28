// Fuzz / random-oracle tests for InboxPullContinuation
// (checklist line 931, 134th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: the pull loop
// continues only on a completed batch with more pages; the ack flush
// fetches more exactly when the batch came back full (fetched >= limit).
// No InboxPullContinuation fuzz test existed before this block
// (content-grep dedup 0 hits on all remote refactor branches, main's
// FuzzTest files, and all open PR titles/bodies).
package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

private val IPC_INT_POOL = intArrayOf(
    Int.MIN_VALUE, Int.MIN_VALUE + 1, -1000, -201, -200, -199, -2, -1, 0, 1, 2,
    199, 200, 201, 1000, Int.MAX_VALUE - 1, Int.MAX_VALUE
)

private fun ipcDecideOracle(batchCompleted: Boolean, hasMore: Boolean): InboxPullContinuation =
    if (batchCompleted && hasMore) InboxPullContinuation.CONTINUE else InboxPullContinuation.STOP

private fun ipcFetchOracle(fetchedCount: Int, limit: Int): Boolean = fetchedCount >= limit

private fun genIpcInt(rnd: Random): Int =
    if (rnd.nextInt(2) == 0) IPC_INT_POOL[rnd.nextInt(IPC_INT_POOL.size)] else rnd.nextInt()

class InboxPullContinuationFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // The pull loop continues only on a completed batch with more pages.
        assertEquals(
            InboxPullContinuation.CONTINUE,
            decideInboxPullContinuation(batchCompleted = true, hasMore = true)
        )
        assertEquals(
            InboxPullContinuation.STOP,
            decideInboxPullContinuation(batchCompleted = true, hasMore = false)
        )
        assertEquals(
            InboxPullContinuation.STOP,
            decideInboxPullContinuation(batchCompleted = false, hasMore = true)
        )
        assertEquals(
            InboxPullContinuation.STOP,
            decideInboxPullContinuation(batchCompleted = false, hasMore = false)
        )
        // A full batch may hide more ids; a short batch means drained.
        assertEquals(true, shouldFetchMoreAcknowledgements(fetchedCount = 200, limit = 200))
        assertEquals(false, shouldFetchMoreAcknowledgements(fetchedCount = 199, limit = 200))
        assertEquals(true, shouldFetchMoreAcknowledgements(fetchedCount = 201, limit = 200))
        // Zero limit: even an empty batch is "full".
        assertEquals(true, shouldFetchMoreAcknowledgements(fetchedCount = 0, limit = 0))
        assertEquals(false, shouldFetchMoreAcknowledgements(fetchedCount = 0, limit = 1))
        assertEquals(true, shouldFetchMoreAcknowledgements(fetchedCount = 0, limit = -1))
        // Negative counts/limits compare as plain integers.
        assertEquals(false, shouldFetchMoreAcknowledgements(fetchedCount = -1, limit = 0))
        assertEquals(true, shouldFetchMoreAcknowledgements(fetchedCount = -1, limit = -1))
        assertEquals(false, shouldFetchMoreAcknowledgements(fetchedCount = -2, limit = -1))
        assertEquals(true, shouldFetchMoreAcknowledgements(fetchedCount = -1, limit = Int.MIN_VALUE))
        // Int extremes: no overflow, plain >= on 32-bit values.
        assertEquals(true, shouldFetchMoreAcknowledgements(fetchedCount = Int.MAX_VALUE, limit = Int.MAX_VALUE))
        assertEquals(false, shouldFetchMoreAcknowledgements(fetchedCount = Int.MAX_VALUE - 1, limit = Int.MAX_VALUE))
        assertEquals(true, shouldFetchMoreAcknowledgements(fetchedCount = Int.MIN_VALUE, limit = Int.MIN_VALUE))
        assertEquals(false, shouldFetchMoreAcknowledgements(fetchedCount = Int.MIN_VALUE, limit = Int.MAX_VALUE))
        assertEquals(true, shouldFetchMoreAcknowledgements(fetchedCount = Int.MAX_VALUE, limit = Int.MIN_VALUE))
        assertEquals(false, shouldFetchMoreAcknowledgements(fetchedCount = Int.MIN_VALUE, limit = -1))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle134.genIpc consumption order exactly, so seed
        // 134001 replays the same 1500 decide + 1500 fetch GEN lines
        // drive134.py ran.
        val rnd = Random(134001)
        repeat(1500) {
            val batchCompleted = rnd.nextBoolean()
            val hasMore = rnd.nextBoolean()
            assertEquals(
                ipcDecideOracle(batchCompleted, hasMore),
                decideInboxPullContinuation(batchCompleted, hasMore)
            )
            val fetchedCount = genIpcInt(rnd)
            val limit = genIpcInt(rnd)
            assertEquals(
                ipcFetchOracle(fetchedCount, limit),
                shouldFetchMoreAcknowledgements(fetchedCount, limit)
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every decide combination.
        for (batchCompleted in listOf(false, true)) {
            for (hasMore in listOf(false, true)) {
                assertEquals(
                    ipcDecideOracle(batchCompleted, hasMore),
                    decideInboxPullContinuation(batchCompleted, hasMore)
                )
            }
        }
        // Every pool value against every pool value.
        for (fetchedCount in IPC_INT_POOL) {
            for (limit in IPC_INT_POOL) {
                assertEquals(
                    ipcFetchOracle(fetchedCount, limit),
                    shouldFetchMoreAcknowledgements(fetchedCount, limit)
                )
            }
        }
        // Dense grid around zero, where sign flips live.
        for (fetchedCount in -5..5) {
            for (limit in -5..5) {
                assertEquals(
                    ipcFetchOracle(fetchedCount, limit),
                    shouldFetchMoreAcknowledgements(fetchedCount, limit)
                )
            }
        }
    }
}
