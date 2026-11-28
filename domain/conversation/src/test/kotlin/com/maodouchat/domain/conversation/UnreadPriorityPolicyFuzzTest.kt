package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [UnreadPriorityPolicy] (checklist line 931, 37th block).
 * Test-only, zero production changes.
 *
 * The oracle re-derives the score from the documented weights: activity is
 * the newer of the two timestamps clamped at zero; muted chats sort by time
 * only; otherwise a marked-unread adds 1e9 and a positive (capped at 999)
 * unread count adds 5e8 plus the count. Random timestamps stay in a
 * realistic range so the score cannot overflow `Long`; overflow is pinned
 * separately with hand-computed wrapping values (verified against an
 * independent Python model). A Python mini-model differentially checks the
 * oracles on 5000 random inputs per group (0 diffs) before this file was
 * written.
 */
class UnreadPriorityPolicyFuzzTest {

    private fun oracleActivityScore(
        lastMessageTime: Long,
        draftUpdatedAt: Long = 0L,
        unreadCount: Int = 0,
        markedUnread: Boolean = false,
        muted: Boolean = false
    ): Long {
        val activity = maxOf(lastMessageTime, draftUpdatedAt).coerceAtLeast(0L)
        if (muted) return activity
        var score = activity
        if (markedUnread) score += UnreadPriorityPolicy.WEIGHT_MARKED_UNREAD
        val unread = unreadCount.coerceIn(0, UnreadPriorityPolicy.MAX_UNREAD_FOR_SCORE)
        if (unread > 0) {
            score += UnreadPriorityPolicy.WEIGHT_HAS_UNREAD + unread
        }
        return score
    }

    private fun oracleShouldShowHint(
        enabled: Boolean,
        totalUnreadChats: Int,
        isSearching: Boolean
    ): Boolean {
        if (!enabled) return false
        if (isSearching) return false
        return totalUnreadChats > 0
    }

    private fun oracleCountUnreadChats(
        unreadCounts: List<Int>,
        markedUnreadFlags: List<Boolean>
    ): Int {
        val n = maxOf(unreadCounts.size, markedUnreadFlags.size)
        var count = 0
        for (i in 0 until n) {
            val u = unreadCounts.getOrElse(i) { 0 }
            val m = markedUnreadFlags.getOrElse(i) { false }
            if (u > 0 || m) count++
        }
        return count
    }

    private fun randomTime(rnd: Random): Long {
        val pool = listOf(0L, 1L, 999L, 1_000L, 9_000L, 1_000_000_000_000L, 10_000_000_000_000L)
        return when (rnd.nextInt(4)) {
            0 -> pool.random(rnd)
            1 -> rnd.nextLong(0L, 1_000_000L)
            2 -> rnd.nextLong(0L, 10_000_000_000_000L)
            else -> rnd.nextLong(-1_000L, 10_000_000_000_000L)
        }
    }

    private fun randomUnreadCount(rnd: Random): Int {
        val pool = listOf(-5, -1, 0, 1, 2, 998, 999, 1000, 9999, Int.MIN_VALUE, Int.MAX_VALUE)
        return when (rnd.nextInt(4)) {
            0 -> pool.random(rnd)
            1 -> rnd.nextInt(-10, 1100)
            2 -> rnd.nextInt(0, 2000)
            else -> rnd.nextInt()
        }
    }

    @Test
    fun activityScoreRandomOracle() {
        val rnd = Random(37)
        repeat(300) {
            val last = randomTime(rnd)
            val draft = randomTime(rnd)
            val unread = randomUnreadCount(rnd)
            val marked = rnd.nextBoolean()
            val muted = rnd.nextBoolean()
            assertEquals(
                oracleActivityScore(last, draft, unread, marked, muted),
                UnreadPriorityPolicy.activityScore(last, draft, unread, marked, muted),
                "last=<$last> draft=<$draft> unread=<$unread> marked=<$marked> muted=<$muted>"
            )
        }
    }

    @Test
    fun shouldShowHintRandomOracle() {
        val rnd = Random(37)
        repeat(300) {
            val enabled = rnd.nextBoolean()
            val total = randomUnreadCount(rnd)
            val searching = rnd.nextBoolean()
            assertEquals(
                oracleShouldShowHint(enabled, total, searching),
                UnreadPriorityPolicy.shouldShowHint(enabled, total, searching),
                "enabled=<$enabled> total=<$total> searching=<$searching>"
            )
        }
    }

    @Test
    fun countUnreadChatsRandomOracle() {
        val rnd = Random(37)
        repeat(300) {
            val counts = List(rnd.nextInt(0, 8)) { randomUnreadCount(rnd) }
            val flags = List(rnd.nextInt(0, 8)) { rnd.nextBoolean() }
            assertEquals(
                oracleCountUnreadChats(counts, flags),
                UnreadPriorityPolicy.countUnreadChats(counts, flags),
                "counts=<$counts> flags=<$flags>"
            )
        }
    }

    @Test
    fun pinnedEdges() {
        // Muted chats sort by time only, even with unread and marked flags.
        assertEquals(
            9_000L,
            UnreadPriorityPolicy.activityScore(9_000L, 0L, 50, true, true)
        )
        // Draft time wins when newer; negative times clamp to zero.
        assertEquals(
            5_000L,
            UnreadPriorityPolicy.activityScore(1_000L, 5_000L, 0, false, false)
        )
        assertEquals(
            0L,
            UnreadPriorityPolicy.activityScore(-5_000L, -1L, 0, false, false)
        )
        // Weights: marked (1e9) beats plain unread (5e8 + capped 999).
        assertEquals(
            1_000_000_000L,
            UnreadPriorityPolicy.activityScore(0L, 0L, 0, true, false)
        )
        assertEquals(
            500_000_999L,
            UnreadPriorityPolicy.activityScore(0L, 0L, 9999, false, false)
        )
        assertEquals(
            1_500_000_999L,
            UnreadPriorityPolicy.activityScore(0L, 0L, 9999, true, false)
        )
        // Long overflow wraps (Kotlin/JVM silent wrap, no exception).
        assertEquals(
            -9_223_372_035_854_775_809L,
            UnreadPriorityPolicy.activityScore(Long.MAX_VALUE, 0L, 0, true, false)
        )
        assertEquals(
            Long.MAX_VALUE,
            UnreadPriorityPolicy.activityScore(Long.MAX_VALUE, 0L, 9999, true, true)
        )
        assertEquals(
            -9_223_372_035_354_774_810L,
            UnreadPriorityPolicy.activityScore(Long.MAX_VALUE, Long.MAX_VALUE, 9999, true, false)
        )
        // Hint gate and unequal-length counting.
        assertTrue(UnreadPriorityPolicy.shouldShowHint(true, 3, false))
        assertFalse(UnreadPriorityPolicy.shouldShowHint(true, -1, false))
        assertEquals(2, UnreadPriorityPolicy.countUnreadChats(listOf(0, 5), listOf(true, false)))
        assertEquals(3, UnreadPriorityPolicy.countUnreadChats(listOf(0), listOf(true, true, false)))
        assertEquals(0, UnreadPriorityPolicy.countUnreadChats(emptyList(), emptyList()))
    }
}
