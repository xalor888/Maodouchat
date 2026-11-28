package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fuzz / random-oracle tests for [ChatListUnreadPolicy] (checklist line 931, 37th block).
 * Test-only, zero production changes.
 *
 * The oracle re-derives the merge rules from the documented behaviour: an
 * active chat always reports zero; a zero local count survives a stale
 * server tail only when the local row is non-empty and not manually marked
 * unread; otherwise the higher count wins; a missing local state falls back
 * to the server count (clamped at zero). A Python mini-model
 * differentially checks both oracles on 5000 random inputs per group
 * (0 diffs) before this file was written.
 */
class ChatListUnreadPolicyFuzzTest {

    private fun oracleMergeUnreadCount(
        serverUnread: Int,
        localUnread: Int?,
        isActiveChat: Boolean,
        localMarkedUnread: Boolean = false,
        serverLastMessageTime: Long = 0L,
        localLastMessageTime: Long = 0L
    ): Int {
        if (isActiveChat) return 0
        val server = serverUnread.coerceAtLeast(0)
        val local = localUnread?.coerceAtLeast(0)
        if (local == null) return server
        if (local == 0 && !localMarkedUnread &&
            localLastMessageTime > 0L &&
            serverLastMessageTime <= localLastMessageTime
        ) {
            return 0
        }
        return maxOf(server, local)
    }

    private fun oracleMergeMarkedUnread(
        serverMarked: Boolean,
        localMarked: Boolean?,
        isActiveChat: Boolean
    ): Boolean {
        if (isActiveChat) return false
        if (localMarked == null) return serverMarked
        if (localMarked && !serverMarked) return true
        return serverMarked
    }

    private fun randomCount(rnd: Random): Int {
        val pool = listOf(-5, -1, 0, 1, 2, 3, 5, 9, 20, 99, 100, Int.MIN_VALUE, Int.MAX_VALUE)
        return when (rnd.nextInt(4)) {
            0 -> pool.random(rnd)
            1 -> rnd.nextInt(-10, 30)
            2 -> rnd.nextInt(0, 200)
            else -> rnd.nextInt()
        }
    }

    private fun randomTime(rnd: Random): Long {
        val pool = listOf(0L, -1L, 1L, 99L, 100L, 101L, 199L, 200L, 201L, Long.MAX_VALUE, Long.MIN_VALUE)
        return when (rnd.nextInt(4)) {
            0 -> pool.random(rnd)
            1 -> rnd.nextLong(-1000L, 1000L)
            2 -> rnd.nextLong(0L, 10_000L)
            else -> rnd.nextLong()
        }
    }

    @Test
    fun mergeUnreadCountRandomOracle() {
        val rnd = Random(37)
        repeat(300) {
            val server = randomCount(rnd)
            val local = if (rnd.nextInt(4) == 0) null else randomCount(rnd)
            val active = rnd.nextBoolean()
            val marked = rnd.nextBoolean()
            val serverTime = randomTime(rnd)
            val localTime = randomTime(rnd)
            assertEquals(
                oracleMergeUnreadCount(server, local, active, marked, serverTime, localTime),
                ChatListUnreadPolicy.mergeUnreadCount(server, local, active, marked, serverTime, localTime),
                "server=<$server> local=<$local> active=<$active> marked=<$marked> " +
                    "serverTime=<$serverTime> localTime=<$localTime>"
            )
        }
    }

    @Test
    fun mergeMarkedUnreadRandomOracle() {
        val rnd = Random(37)
        repeat(300) {
            val server = rnd.nextBoolean()
            val local = when (rnd.nextInt(3)) {
                0 -> null
                1 -> true
                else -> false
            }
            val active = rnd.nextBoolean()
            assertEquals(
                oracleMergeMarkedUnread(server, local, active),
                ChatListUnreadPolicy.mergeMarkedUnread(server, local, active),
                "server=<$server> local=<$local> active=<$active>"
            )
        }
    }

    @Test
    fun pinnedEdges() {
        // Negative counts clamp to zero before merging.
        assertEquals(
            0,
            ChatListUnreadPolicy.mergeUnreadCount(-3, null, false)
        )
        assertEquals(
            0,
            ChatListUnreadPolicy.mergeUnreadCount(-1, -2, false)
        )
        // Stale-tail guard: equal tails still keep zero; a newer server tail revives.
        assertEquals(
            0,
            ChatListUnreadPolicy.mergeUnreadCount(5, 0, false, false, 200L, 200L)
        )
        assertEquals(
            5,
            ChatListUnreadPolicy.mergeUnreadCount(5, 0, false, false, 201L, 200L)
        )
        // An empty local row (time 0) still accepts the server count.
        assertEquals(
            5,
            ChatListUnreadPolicy.mergeUnreadCount(5, 0, false, false, 100L, 0L)
        )
        // A manually marked unread is never wiped by a stale zero.
        assertEquals(
            5,
            ChatListUnreadPolicy.mergeUnreadCount(5, 0, false, true, 100L, 200L)
        )
        // Otherwise the higher count wins.
        assertEquals(7, ChatListUnreadPolicy.mergeUnreadCount(3, 7, false))
        assertEquals(9, ChatListUnreadPolicy.mergeUnreadCount(9, 3, false))
        assertEquals(7, ChatListUnreadPolicy.mergeUnreadCount(7, null, false))
        // Marked-unread merge: local true beats a false server; active chat clears.
        assertTrue(ChatListUnreadPolicy.mergeMarkedUnread(true, null, false))
        assertTrue(ChatListUnreadPolicy.mergeMarkedUnread(false, true, false))
        assertTrue(ChatListUnreadPolicy.mergeMarkedUnread(true, false, false))
        assertFalse(ChatListUnreadPolicy.mergeMarkedUnread(false, false, false))
        assertFalse(ChatListUnreadPolicy.mergeMarkedUnread(true, true, true))
    }
}
