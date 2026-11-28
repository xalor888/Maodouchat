// Fuzz / random-oracle tests for GroupAiShareDecision
// (checklist line 931, 133rd block). Test-only, zero production changes.
// The oracle is hand-written from the production source: non-group
// refuses first (NOT_GROUP), then already-shared (ALREADY_SHARED),
// then a blank-after-trim answer (EMPTY_ANSWER); otherwise the trimmed
// body is shared, capped at 4000 chars. No GroupAiShareDecision fuzz
// test existed before this block (content-grep dedup 0 hits on all
// remote refactor branches, main's FuzzTest files, and all open PR
// titles/bodies).
package com.maodouchat.domain.groups

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

private fun gasDecideOracle(
    isGroup: Boolean,
    answer: String?,
    alreadyShared: Boolean
): ShareDecision {
    if (!isGroup) return ShareDecision(false, reason = BlockReason.NOT_GROUP)
    if (alreadyShared) return ShareDecision(false, reason = BlockReason.ALREADY_SHARED)
    val body = answer?.trim().orEmpty()
    if (body.isEmpty()) return ShareDecision(false, reason = BlockReason.EMPTY_ANSWER)
    return ShareDecision(true, body = body.take(GroupAiShareDecision.MAX_SHARE_CHARS))
}

// ---------- generators (mirrored by Oracle133 GEN, seed 133001) ----------

private val GAS_ANSWER_POOL = arrayOf(
    "", " ", "hello", "  hello  ", "Hello World", "x", "xx",
    "\u00A0", "a\uD800b", "\uD83D\uDE00", "\u4E2D\u6587\u56DE\u7B54",
    "line1\nline2", "\tTabbed\t", "answer", "  answer  ", "\u2003",
    "a b", "A"
)

private fun genGasAnswer(rnd: Random): String? {
    if (rnd.nextInt(8) == 0) return null
    if (rnd.nextInt(10) == 0) {
        val padL = rnd.nextInt(0, 3)
        val n = rnd.nextInt(3995, 4006)
        val padR = rnd.nextInt(0, 3)
        return " ".repeat(padL) + "a".repeat(n) + " ".repeat(padR)
    }
    if (rnd.nextInt(2) == 0) return GAS_ANSWER_POOL[rnd.nextInt(GAS_ANSWER_POOL.size)]
    return randomText(rnd, 12)
}

class GroupAiShareDecisionFuzzTest {

    @Test
    fun pinnedBoundaries() {
        assertEquals(4000, GroupAiShareDecision.MAX_SHARE_CHARS)
        // Refusal order: group first, then already-shared, then empty.
        assertEquals(
            ShareDecision(false, reason = BlockReason.NOT_GROUP),
            GroupAiShareDecision.decideShare(false, "x", alreadyShared = true)
        )
        assertEquals(
            ShareDecision(false, reason = BlockReason.NOT_GROUP),
            GroupAiShareDecision.decideShare(false, null)
        )
        assertEquals(
            ShareDecision(false, reason = BlockReason.ALREADY_SHARED),
            GroupAiShareDecision.decideShare(true, "x", alreadyShared = true)
        )
        assertEquals(
            ShareDecision(false, reason = BlockReason.ALREADY_SHARED),
            GroupAiShareDecision.decideShare(true, null, alreadyShared = true)
        )
        assertEquals(
            ShareDecision(false, reason = BlockReason.EMPTY_ANSWER),
            GroupAiShareDecision.decideShare(true, null)
        )
        assertEquals(
            ShareDecision(false, reason = BlockReason.EMPTY_ANSWER),
            GroupAiShareDecision.decideShare(true, "")
        )
        assertEquals(
            ShareDecision(false, reason = BlockReason.EMPTY_ANSWER),
            GroupAiShareDecision.decideShare(true, "   ")
        )
        assertEquals(
            ShareDecision(false, reason = BlockReason.EMPTY_ANSWER),
            GroupAiShareDecision.decideShare(true, "\u00A0\u2003")
        )
        // Trimmed body is shared as-is.
        assertEquals(
            ShareDecision(true, body = "answer"),
            GroupAiShareDecision.decideShare(true, "  answer  ")
        )
        assertEquals(
            ShareDecision(true, body = "x"),
            GroupAiShareDecision.decideShare(true, "x")
        )
        // Cap at exactly 4000 chars, counted after trimming.
        val body4000 = "x".repeat(4000)
        assertEquals(
            ShareDecision(true, body = body4000),
            GroupAiShareDecision.decideShare(true, body4000)
        )
        assertEquals(
            ShareDecision(true, body = body4000),
            GroupAiShareDecision.decideShare(true, "x".repeat(4001))
        )
        assertEquals(
            ShareDecision(true, body = body4000),
            GroupAiShareDecision.decideShare(true, "x".repeat(5000))
        )
        assertEquals(
            ShareDecision(true, body = body4000),
            GroupAiShareDecision.decideShare(true, "  " + "x".repeat(4001) + "  ")
        )
        // take() counts UTF-16 units: a lone surrogate at the cap edge stays.
        val edge = "a".repeat(3999) + "\uD800" + "b"
        assertEquals(4001, edge.length)
        assertEquals(
            ShareDecision(true, body = "a".repeat(3999) + "\uD800"),
            GroupAiShareDecision.decideShare(true, edge)
        )
        assertEquals(
            ShareDecision(true, body = "a".repeat(3999) + "a"),
            GroupAiShareDecision.decideShare(true, "a".repeat(3999) + "ab")
        )
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle133.genGas consumption order exactly, so seed
        // 133001 replays the same 1500 GEN lines drive133.py ran.
        val rnd = Random(133001)
        repeat(1500) {
            val isGroup = rnd.nextBoolean()
            val answer = genGasAnswer(rnd)
            val alreadyShared = rnd.nextBoolean()
            assertEquals(
                gasDecideOracle(isGroup, answer, alreadyShared),
                GroupAiShareDecision.decideShare(isGroup, answer, alreadyShared)
            )
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool answer through all four flag combinations.
        for (answer in GAS_ANSWER_POOL) {
            for (isGroup in listOf(false, true)) {
                for (alreadyShared in listOf(false, true)) {
                    assertEquals(
                        gasDecideOracle(isGroup, answer, alreadyShared),
                        GroupAiShareDecision.decideShare(isGroup, answer, alreadyShared)
                    )
                }
            }
        }
        // Cap boundary lengths, with and without surrounding spaces.
        for (n in 3998..4002) {
            for (pad in listOf("", " ", "  ")) {
                val answer = pad + "a".repeat(n) + pad
                assertEquals(
                    gasDecideOracle(true, answer, false),
                    GroupAiShareDecision.decideShare(true, answer, false)
                )
            }
        }
        // Whitespace/control singles as answers.
        for (u in WS_CHARS) {
            val s = u.toString()
            assertEquals(
                gasDecideOracle(true, s, false),
                GroupAiShareDecision.decideShare(true, s, false)
            )
        }
        for (code in 0x00 until 0x20) {
            val s = code.toChar().toString()
            assertEquals(
                gasDecideOracle(true, s, false),
                GroupAiShareDecision.decideShare(true, s, false)
            )
        }
    }
}
