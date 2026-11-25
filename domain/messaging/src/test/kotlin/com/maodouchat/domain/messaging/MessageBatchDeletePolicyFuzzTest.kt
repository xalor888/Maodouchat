// Fuzz / random-oracle tests for MessageBatchDeletePolicy
// (checklist line 931, 158th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: only the
// current user's own messages are deletable, the deletable batch is
// capped at 60 (the server mutation limit), cappedOut counts the
// deletable messages beyond the cap, skippedCount counts the messages
// sent by others, and the batch is confirmable exactly when the capped
// batch is non-empty. Sender comparison is exact (case-sensitive).
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

internal const val BATCH_CAP_ORACLE158 = 60

internal fun batchDeleteOracle158(messages: List<Message>, currentUserId: String): MessageBatchDelete {
    val deletable = messages.filter { it.senderId == currentUserId }
    val capped = deletable.take(BATCH_CAP_ORACLE158)
    return MessageBatchDelete(
        cappedIds = capped.map { it.id },
        cappedCount = capped.size,
        cappedOut = deletable.size - capped.size,
        skippedCount = messages.size - deletable.size,
        canConfirm = capped.isNotEmpty(),
    )
}

// ---------- generators ----------

internal val BATCH_SENDER_POOL158 = listOf("me", "other", "", "ME", "Me", "me ", " me", "u1", "u2")

internal val BATCH_ME_POOL158 = listOf("me", "other", "", "ME", "u1")

internal fun randomBatchMessage158(rnd: Random, index: Int): Message = Message(
    id = "m$index",
    chatId = "c",
    senderId = if (rnd.nextInt(10) < 6) BATCH_SENDER_POOL158[rnd.nextInt(BATCH_SENDER_POOL158.size)] else "s" + rnd.nextInt(100),
    content = "",
)

internal data class BatchCase158(
    val messages: List<Message>,
    val me: String,
)

internal fun randomBatchCase158(rnd: Random): BatchCase158 {
    val count = when (rnd.nextInt(10)) {
        0 -> 0
        1 -> rnd.nextInt(55, 71)
        2 -> rnd.nextInt(115, 131)
        else -> rnd.nextInt(0, 20)
    }
    val me = BATCH_ME_POOL158[rnd.nextInt(BATCH_ME_POOL158.size)]
    val messages = List(count) { i -> randomBatchMessage158(rnd, i) }
    return BatchCase158(messages, me)
}

internal fun batchJsonEscape158(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            else -> {
                if (c.code < 0x20) {
                    val v = c.code
                    sb.append("\\u")
                    sb.append("0123456789abcdef"[v shr 12])
                    sb.append("0123456789abcdef"[(v shr 8) and 0xF])
                    sb.append("0123456789abcdef"[(v shr 4) and 0xF])
                    sb.append("0123456789abcdef"[v and 0xF])
                } else {
                    sb.append(c)
                }
            }
        }
    }
    return sb.toString()
}

internal fun batchMessageJson158(message: Message): String =
    "{\"id\":\"" + batchJsonEscape158(message.id) + "\",\"sender\":\"" +
        batchJsonEscape158(message.senderId) + "\"}"

internal fun batchResolveWireJson158(case: BatchCase158): String {
    val sb = StringBuilder()
    sb.append("{\"msgs\":[")
    case.messages.forEachIndexed { i, m ->
        if (i > 0) sb.append(",")
        sb.append(batchMessageJson158(m))
    }
    sb.append("],\"me\":\"").append(batchJsonEscape158(case.me)).append("\"}")
    return sb.toString()
}

class MessageBatchDeletePolicyFuzzTest {

    private fun check(messages: List<Message>, me: String) {
        assertEquals(
            batchDeleteOracle158(messages, me),
            MessageBatchDeletePolicy.resolve(messages, me),
        )
    }

    private fun msg(id: String, sender: String): Message =
        Message(id = id, chatId = "c", senderId = sender, content = "")

    @Test
    fun pinnedBoundaries() {
        assertEquals(60, MessageBatchDeletePolicy.BATCH_CAP)
        // Empty selection: nothing deletable, nothing confirmable.
        check(emptyList(), "me")
        assertEquals(
            MessageBatchDelete(
                cappedIds = emptyList(), cappedCount = 0, cappedOut = 0,
                skippedCount = 0, canConfirm = false,
            ),
            batchDeleteOracle158(emptyList(), "me"),
        )
        // All own: capped whole, no overflow, no skips.
        val threeOwn = listOf(msg("m0", "me"), msg("m1", "me"), msg("m2", "me"))
        assertEquals(
            MessageBatchDelete(
                cappedIds = listOf("m0", "m1", "m2"), cappedCount = 3, cappedOut = 0,
                skippedCount = 0, canConfirm = true,
            ),
            MessageBatchDeletePolicy.resolve(threeOwn, "me"),
        )
        // All others: capped empty, all skipped, not confirmable.
        val threeOthers = listOf(msg("m0", "u1"), msg("m1", "u2"), msg("m2", "u1"))
        assertEquals(
            MessageBatchDelete(
                cappedIds = emptyList(), cappedCount = 0, cappedOut = 0,
                skippedCount = 3, canConfirm = false,
            ),
            MessageBatchDeletePolicy.resolve(threeOthers, "me"),
        )
        // Mixed: order preserved, others skipped, cap untouched.
        val mixed = listOf(
            msg("m0", "u1"), msg("m1", "me"), msg("m2", "u2"),
            msg("m3", "me"), msg("m4", "me"),
        )
        assertEquals(
            MessageBatchDelete(
                cappedIds = listOf("m1", "m3", "m4"), cappedCount = 3, cappedOut = 0,
                skippedCount = 2, canConfirm = true,
            ),
            MessageBatchDeletePolicy.resolve(mixed, "me"),
        )
        // Sender comparison is exact: "ME" is not "me".
        val caseSensitive = listOf(msg("m0", "ME"), msg("m1", "Me"), msg("m2", "me"))
        assertEquals(
            MessageBatchDelete(
                cappedIds = listOf("m2"), cappedCount = 1, cappedOut = 0,
                skippedCount = 2, canConfirm = true,
            ),
            MessageBatchDeletePolicy.resolve(caseSensitive, "me"),
        )
        // Exactly at the cap: 60 own, no overflow.
        val sixty = List(60) { i -> msg("m$i", "me") }
        assertEquals(
            MessageBatchDelete(
                cappedIds = (0 until 60).map { "m$it" }, cappedCount = 60, cappedOut = 0,
                skippedCount = 0, canConfirm = true,
            ),
            MessageBatchDeletePolicy.resolve(sixty, "me"),
        )
        // One over the cap: the 61st own message overflows.
        val sixtyOne = List(61) { i -> msg("m$i", "me") }
        assertEquals(
            MessageBatchDelete(
                cappedIds = (0 until 60).map { "m$it" }, cappedCount = 60, cappedOut = 1,
                skippedCount = 0, canConfirm = true,
            ),
            MessageBatchDeletePolicy.resolve(sixtyOne, "me"),
        )
        // Cap plus skips: 61 own + 5 others.
        val sixtyOnePlusFive = sixtyOne + List(5) { i -> msg("x$i", "u1") }
        assertEquals(
            MessageBatchDelete(
                cappedIds = (0 until 60).map { "m$it" }, cappedCount = 60, cappedOut = 1,
                skippedCount = 5, canConfirm = true,
            ),
            MessageBatchDeletePolicy.resolve(sixtyOnePlusFive, "me"),
        )
        // 59 own + 5 others: under the cap, all own kept.
        val fiftyNinePlusFive = List(59) { i -> msg("m$i", "me") } + List(5) { i -> msg("x$i", "u1") }
        assertEquals(
            MessageBatchDelete(
                cappedIds = (0 until 59).map { "m$it" }, cappedCount = 59, cappedOut = 0,
                skippedCount = 5, canConfirm = true,
            ),
            MessageBatchDeletePolicy.resolve(fiftyNinePlusFive, "me"),
        )
        // Empty current-user id still matches empty senders exactly.
        val emptyMe = listOf(msg("m0", ""), msg("m1", "me"))
        assertEquals(
            MessageBatchDelete(
                cappedIds = listOf("m0"), cappedCount = 1, cappedOut = 0,
                skippedCount = 1, canConfirm = true,
            ),
            MessageBatchDeletePolicy.resolve(emptyMe, ""),
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        val rnd = Random(158003)
        repeat(2000) {
            val case = randomBatchCase158(rnd)
            check(case.messages, case.me)
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every total count 0..130 of all-own messages.
        for (n in 0..130) {
            val messages = List(n) { i -> msg("m$i", "me") }
            assertEquals(
                MessageBatchDelete(
                    cappedIds = (0 until minOf(n, 60)).map { "m$it" },
                    cappedCount = minOf(n, 60),
                    cappedOut = n - minOf(n, 60),
                    skippedCount = 0,
                    canConfirm = n > 0,
                ),
                MessageBatchDeletePolicy.resolve(messages, "me"),
            )
        }
        // Every total count 0..130 of all-other messages.
        for (n in 0..130) {
            val messages = List(n) { i -> msg("m$i", "u1") }
            assertEquals(
                MessageBatchDelete(
                    cappedIds = emptyList(), cappedCount = 0, cappedOut = 0,
                    skippedCount = n, canConfirm = false,
                ),
                MessageBatchDeletePolicy.resolve(messages, "me"),
            )
        }
        // Every own-count 0..70 mixed with a fixed 5 others.
        for (own in 0..70) {
            val messages = List(own) { i -> msg("m$i", "me") } +
                List(5) { i -> msg("x$i", "u1") }
            assertEquals(
                batchDeleteOracle158(messages, "me"),
                MessageBatchDeletePolicy.resolve(messages, "me"),
            )
        }
        // Every own-count 0..70 interleaved with others (own, other, ...).
        for (own in 0..70) {
            val messages = buildList {
                repeat(own) { i ->
                    add(msg("m$i", "me"))
                    add(msg("x$i", "u1"))
                }
            }
            assertEquals(
                batchDeleteOracle158(messages, "me"),
                MessageBatchDeletePolicy.resolve(messages, "me"),
            )
        }
        // Every sender in the pool as the current user, fixed selection.
        val selection = listOf(
            msg("m0", "me"), msg("m1", "other"), msg("m2", ""),
            msg("m3", "ME"), msg("m4", "u1"),
        )
        for (me in BATCH_SENDER_POOL158 + BATCH_ME_POOL158) {
            assertEquals(
                batchDeleteOracle158(selection, me),
                MessageBatchDeletePolicy.resolve(selection, me),
            )
        }
    }
}
