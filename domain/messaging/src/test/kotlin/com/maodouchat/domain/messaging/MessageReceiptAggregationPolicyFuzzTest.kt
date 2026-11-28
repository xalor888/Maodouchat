// Fuzz / random-oracle tests for MessageReceiptAggregationPolicy
// (checklist line 931, 132nd block). Test-only, zero production changes.
// The oracle is hand-written from the production source: each of the
// three receipt timestamps takes the max of the existing value and the
// new value (missing counts as 0), and drops back to null when that
// max is not positive; the four id fields pass through and updatedAt
// is the supplied now. No mergeReceipt fuzz test existed before this
// block (content-grep dedup 0 hits on all 138 remote branches, main's
// 127 FuzzTest files, and all 135 open PR titles/bodies; the bare
// model name MessageReceipt also appears in MessageReceiptGuard /
// MessageReceiptId / ChatLockPolicy fuzz tests, which cover other
// functions, not mergeReceipt).
package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageReceipt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- shared random-text helpers ----------

private val WS_CHARS = charArrayOf(
    '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u001C', '\u001D',
    '\u001E', '\u001F', ' ', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
    '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008', '\u2009',
    '\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000'
)

private val TEXT_RANGES = arrayOf(
    0x20 to 0x7E,
    0x00A0 to 0x00FF, 0x0100 to 0x017F, 0x0370 to 0x03FF, 0x0400 to 0x04FF,
    0x2000 to 0x206F, 0x3000 to 0x30FF, 0x4E00 to 0x4FFF, 0xFF00 to 0xFFEF,
    0x10000 to 0x1FFFF, 0x20000 to 0x2A6DF
)

private fun randomCodePoint(rnd: Random): Int {
    val (lo, hi) = TEXT_RANGES[rnd.nextInt(TEXT_RANGES.size)]
    return rnd.nextInt(lo, hi + 1)
}

private fun randomUnit(rnd: Random, sb: StringBuilder) {
    when (rnd.nextInt(20)) {
        0 -> sb.append(WS_CHARS[rnd.nextInt(WS_CHARS.size)])
        1 -> sb.append(rnd.nextInt(0x00, 0x20).toChar())
        2 -> {
            val hi = rnd.nextBoolean()
            sb.append(if (hi) rnd.nextInt(0xD800, 0xDC00).toChar() else rnd.nextInt(0xDC00, 0xE000).toChar())
        }
        else -> sb.append(Character.toChars(randomCodePoint(rnd)))
    }
}

private fun randomText(rnd: Random, maxLen: Int): String {
    val len = rnd.nextInt(0, maxLen + 1)
    val sb = StringBuilder()
    repeat(len) { randomUnit(rnd, sb) }
    return sb.toString()
}

// ---------- oracle ----------

private fun mrapMergeOracle(
    existing: MessageReceipt?,
    ownerUserId: String,
    messageId: String,
    conversationId: String,
    recipientUserId: String,
    deliveredAt: Long?,
    readAt: Long? = null,
    playedAt: Long? = null,
    now: Long,
): MessageReceipt = MessageReceipt(
    ownerUserId = ownerUserId,
    messageId = messageId,
    conversationId = conversationId,
    recipientUserId = recipientUserId,
    deliveredAt = maxOf(existing?.deliveredAt ?: 0L, deliveredAt ?: 0L).takeIf { it > 0L },
    readAt = maxOf(existing?.readAt ?: 0L, readAt ?: 0L).takeIf { it > 0L },
    playedAt = maxOf(existing?.playedAt ?: 0L, playedAt ?: 0L).takeIf { it > 0L },
    updatedAt = now,
)

// ---------- generators (mirrored by Oracle132 GEN, seed 132003) ----------

private val MRAP_ID_POOL = arrayOf(
    "", " ", "u1", "u2", "m1", "c1", "a", "b", "a\uD800b", "\uDC00",
    "\uE000", "\uD83D\uDE00", "u10", "  ", "\u00A0"
)

private val MRAP_LONG_POOL = longArrayOf(
    Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, 2L, 1000L,
    86400000L, Long.MAX_VALUE - 1, Long.MAX_VALUE
)

private fun genMrapId(rnd: Random): String =
    if (rnd.nextInt(2) == 0) MRAP_ID_POOL[rnd.nextInt(MRAP_ID_POOL.size)]
    else randomText(rnd, 6)

private fun genMrapLong(rnd: Random): Long =
    if (rnd.nextInt(2) == 0) MRAP_LONG_POOL[rnd.nextInt(MRAP_LONG_POOL.size)]
    else rnd.nextLong(-1_000_000_000L, 1_000_000_001L)

private fun genMrapLongNullable(rnd: Random): Long? =
    if (rnd.nextInt(3) == 0) null else genMrapLong(rnd)

private fun genMrapReceipt(rnd: Random): MessageReceipt = MessageReceipt(
    ownerUserId = genMrapId(rnd),
    messageId = genMrapId(rnd),
    conversationId = genMrapId(rnd),
    recipientUserId = genMrapId(rnd),
    deliveredAt = genMrapLongNullable(rnd),
    readAt = genMrapLongNullable(rnd),
    playedAt = genMrapLongNullable(rnd),
    updatedAt = genMrapLong(rnd)
)

private fun genMrapReceiptNullable(rnd: Random): MessageReceipt? =
    if (rnd.nextInt(4) == 0) null else genMrapReceipt(rnd)

class MessageReceiptAggregationPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        val base = MessageReceipt("o", "m", "c", "r", 100L, 200L, 300L, 999L)
        // Timestamps never move backward; the new value wins only when larger.
        assertEquals(
            MessageReceipt("o", "m", "c", "r", 100L, 250L, 300L, 1000L),
            MessageReceiptAggregationPolicy.mergeReceipt(base, "o", "m", "c", "r", 50L, 250L, 299L, 1000L)
        )
        // A missing existing receipt starts from 0; a missing new value keeps the old one.
        assertEquals(
            MessageReceipt("o", "m", "c", "r", 5L, null, 7L, 1000L),
            MessageReceiptAggregationPolicy.mergeReceipt(null, "o", "m", "c", "r", 5L, null, 7L, 1000L)
        )
        assertEquals(
            MessageReceipt("o", "m", "c", "r", 100L, 200L, 300L, 1000L),
            MessageReceiptAggregationPolicy.mergeReceipt(base, "o", "m", "c", "r", null, null, null, 1000L)
        )
        // Zero or negative maxima drop back to null, on each field independently.
        assertEquals(
            MessageReceipt("o", "m", "c", "r", null, null, null, 1000L),
            MessageReceiptAggregationPolicy.mergeReceipt(null, "o", "m", "c", "r", 0L, -5L, null, 1000L)
        )
        assertEquals(
            MessageReceipt("o", "m", "c", "r", null, 200L, null, 1000L),
            MessageReceiptAggregationPolicy.mergeReceipt(
                MessageReceipt("o", "m", "c", "r", -1L, 200L, 0L, 999L),
                "o", "m", "c", "r", 0L, -1L, -2L, 1000L
            )
        )
        // Long extremes: maxOf never overflows, and a stale Long.MAX_VALUE sticks.
        assertEquals(
            MessageReceipt("o", "m", "c", "r", Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 1000L),
            MessageReceiptAggregationPolicy.mergeReceipt(
                MessageReceipt("o", "m", "c", "r", Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 999L),
                "o", "m", "c", "r", Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, 1000L
            )
        )
        // Id fields pass through from the call arguments, not from the existing receipt.
        assertEquals(
            MessageReceipt("o2", "m2", "c2", "r2", 100L, 200L, 300L, 1000L),
            MessageReceiptAggregationPolicy.mergeReceipt(base, "o2", "m2", "c2", "r2", 1L, 1L, 1L, 1000L)
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle132.genMrap consumption order exactly, so seed
        // 132003 replays the same 1500 GEN lines drive132.py ran.
        val rnd = Random(132003)
        repeat(1500) {
            val existing = genMrapReceiptNullable(rnd)
            val owner = genMrapId(rnd)
            val message = genMrapId(rnd)
            val conversation = genMrapId(rnd)
            val recipient = genMrapId(rnd)
            val delivered = genMrapLongNullable(rnd)
            val read = genMrapLongNullable(rnd)
            val played = genMrapLongNullable(rnd)
            val now = genMrapLong(rnd)
            assertEquals(
                mrapMergeOracle(existing, owner, message, conversation, recipient, delivered, read, played, now),
                MessageReceiptAggregationPolicy.mergeReceipt(existing, owner, message, conversation, recipient, delivered, read, played, now)
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool timestamp against every other, on each field.
        val existing = MessageReceipt("o", "m", "c", "r", null, null, null, 0L)
        for (a in MRAP_LONG_POOL) {
            for (b in MRAP_LONG_POOL) {
                val ex = MessageReceipt("o", "m", "c", "r", a, a, a, 0L)
                assertEquals(
                    mrapMergeOracle(ex, "o", "m", "c", "r", b, b, b, 1L),
                    MessageReceiptAggregationPolicy.mergeReceipt(ex, "o", "m", "c", "r", b, b, b, 1L)
                )
                assertEquals(
                    mrapMergeOracle(existing, "o", "m", "c", "r", b, null, null, 1L),
                    MessageReceiptAggregationPolicy.mergeReceipt(existing, "o", "m", "c", "r", b, null, null, 1L)
                )
            }
        }
        // Every pool id through each id slot.
        for (id in MRAP_ID_POOL) {
            assertEquals(
                mrapMergeOracle(null, id, "m", "c", "r", 5L, 5L, 5L, 1L),
                MessageReceiptAggregationPolicy.mergeReceipt(null, id, "m", "c", "r", 5L, 5L, 5L, 1L)
            )
            assertEquals(
                mrapMergeOracle(null, "o", id, "c", "r", 5L, 5L, 5L, 1L),
                MessageReceiptAggregationPolicy.mergeReceipt(null, "o", id, "c", "r", 5L, 5L, 5L, 1L)
            )
        }
        // Whitespace/control singles as ids.
        for (u in WS_CHARS) {
            val s = u.toString()
            assertEquals(
                mrapMergeOracle(null, s, "m", "c", "r", 5L, 5L, 5L, 1L),
                MessageReceiptAggregationPolicy.mergeReceipt(null, s, "m", "c", "r", 5L, 5L, 5L, 1L)
            )
        }
        for (code in 0x00 until 0x20) {
            val s = code.toChar().toString()
            assertEquals(
                mrapMergeOracle(null, s, "m", "c", "r", 5L, 5L, 5L, 1L),
                MessageReceiptAggregationPolicy.mergeReceipt(null, s, "m", "c", "r", 5L, 5L, 5L, 1L)
            )
        }
    }
}
