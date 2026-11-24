// Fuzz / random-oracle tests for SenderKeyCoveragePolicy
// (checklist line 931, 128th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: the decision
// matches the historical requiresDistribution contract (no local mint,
// epoch mismatch, any non-SENT target, empty list after an epoch match
// is complete unless the server explicitly reported zero with epoch 0).
// equals(ignoreCase) is reimplemented per char with
// Character.toUpperCase/toLowerCase, never the stdlib fold. No
// SenderKeyCoveragePolicy fuzz test existed before this block.
package com.maodouchat.core.crypto

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- shared oracle ----------

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

private fun eqIgnoreCaseOracle(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    for (i in a.indices) {
        val x = a[i]
        val y = b[i]
        if (x == y) continue
        if (Character.toUpperCase(x) == Character.toUpperCase(y)) continue
        if (Character.toLowerCase(x) == Character.toLowerCase(y)) continue
        return false
    }
    return true
}

private data class AssessmentOracle(
    val requiresDistribution: Boolean,
    val reason: String,
    val failedCount: Int = 0,
    val pendingCount: Int = 0,
    val unknownCount: Int = 0,
    val sentCount: Int = 0,
)

private fun skcAssessOracle(
    hasLocalDistribution: Boolean,
    requestedEpoch: Long,
    statusEpoch: Long,
    targetStatuses: List<String>,
    reportedTotal: Int? = null,
): AssessmentOracle {
    if (!hasLocalDistribution) {
        return AssessmentOracle(true, "LOCAL_MISSING")
    }
    if (statusEpoch != requestedEpoch) {
        return AssessmentOracle(true, "EPOCH_MISMATCH")
    }
    val total = reportedTotal ?: targetStatuses.size
    if (total <= 0 && targetStatuses.isEmpty()) {
        return if (reportedTotal == 0 && statusEpoch <= 0L) {
            AssessmentOracle(true, "NO_SERVER_RECORD")
        } else {
            AssessmentOracle(false, "COMPLETE")
        }
    }
    var failed = 0
    var pending = 0
    var unknown = 0
    var sent = 0
    targetStatuses.forEach { raw ->
        when {
            eqIgnoreCaseOracle(raw, "SENT") -> sent++
            eqIgnoreCaseOracle(raw, "FAILED") -> failed++
            eqIgnoreCaseOracle(raw, "PENDING") -> pending++
            else -> unknown++
        }
    }
    return when {
        failed > 0 -> AssessmentOracle(true, "FAILED_TARGETS", failed, pending, unknown, sent)
        pending > 0 -> AssessmentOracle(true, "PENDING_TARGETS", failed, pending, unknown, sent)
        unknown > 0 -> AssessmentOracle(true, "UNKNOWN_TARGETS", failed, pending, unknown, sent)
        else -> AssessmentOracle(false, "COMPLETE", failed, pending, unknown, sent)
    }
}

private fun skcRequiresOracle(
    hasLocalDistribution: Boolean,
    requestedEpoch: Long,
    statusEpoch: Long,
    targetStatuses: List<String>,
): Boolean = skcAssessOracle(
    hasLocalDistribution = hasLocalDistribution,
    requestedEpoch = requestedEpoch,
    statusEpoch = statusEpoch,
    targetStatuses = targetStatuses
).requiresDistribution

private val SKC_STATUS_POOL = arrayOf(
    "SENT", "sent", "sEnT", "SeNt", "ſENT", "ſent",
    "FAILED", "failed", "FaIlEd",
    "PENDING", "pending", "PeNdInG",
    "SENT ", " SENT", "", "UNKNOWN", "DELIVERED",
    "SENT\u00A0", "SENT\n", "SENT\u2003", "FAILED\u2003", "pend\u0130ng"
)

private val SKC_EPOCH_POOL = longArrayOf(
    Long.MIN_VALUE, -1, 0, 1, 2, 5, 100, Long.MAX_VALUE
)

private val SKC_TOTAL_POOL = arrayOf<Int?>(
    null, -2, -1, 0, 1, 2, 3, 100, Int.MIN_VALUE, Int.MAX_VALUE
)

private fun genSkcBool(rnd: Random): Boolean = rnd.nextBoolean()

private fun genSkcStatus(rnd: Random): String =
    if (rnd.nextInt(3) == 0) SKC_STATUS_POOL[rnd.nextInt(SKC_STATUS_POOL.size)] else randomText(rnd, 8)

private fun genSkcEpoch(rnd: Random): Long =
    if (rnd.nextInt(3) == 0) SKC_EPOCH_POOL[rnd.nextInt(SKC_EPOCH_POOL.size)] else rnd.nextLong(-10, 11)

private fun genSkcTotal(rnd: Random): Int? =
    if (rnd.nextInt(3) == 0) SKC_TOTAL_POOL[rnd.nextInt(SKC_TOTAL_POOL.size)] else rnd.nextInt(-5, 11)

private fun genSkcTargetList(rnd: Random): List<String> {
    val n = rnd.nextInt(0, 5)
    val out = ArrayList<String>(n)
    repeat(n) { out.add(genSkcStatus(rnd)) }
    return out
}

class SenderKeyCoveragePolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // No local mint always distributes, whatever the epoch says.
        assertEquals(Assessment(true, Reason.LOCAL_MISSING),
            SenderKeyCoveragePolicy.assess(false, 1, 1, emptyList(), null))
        assertEquals(Assessment(true, Reason.LOCAL_MISSING),
            SenderKeyCoveragePolicy.assess(false, 1, 2, listOf("SENT"), 3))
        // Epoch mismatch always distributes.
        assertEquals(Assessment(true, Reason.EPOCH_MISMATCH),
            SenderKeyCoveragePolicy.assess(true, 1, 2, emptyList(), null))
        assertEquals(Assessment(true, Reason.EPOCH_MISMATCH),
            SenderKeyCoveragePolicy.assess(true, Long.MIN_VALUE, Long.MAX_VALUE, listOf("SENT"), null))
        // Empty list after an epoch match is complete, except when the
        // server explicitly reported zero with epoch 0 (no record).
        assertEquals(Assessment(false, Reason.COMPLETE),
            SenderKeyCoveragePolicy.assess(true, 1, 1, emptyList(), null))
        assertEquals(Assessment(false, Reason.COMPLETE),
            SenderKeyCoveragePolicy.assess(true, 0, 0, emptyList(), null))
        assertEquals(Assessment(true, Reason.NO_SERVER_RECORD),
            SenderKeyCoveragePolicy.assess(true, 0, 0, emptyList(), 0))
        assertEquals(Assessment(false, Reason.COMPLETE),
            SenderKeyCoveragePolicy.assess(true, 1, 1, emptyList(), 0))
        assertEquals(Assessment(false, Reason.COMPLETE),
            SenderKeyCoveragePolicy.assess(true, 1, 1, emptyList(), 3))
        // Case-insensitive status words: "ſENT" (long s) counts as sent.
        assertEquals(Assessment(false, Reason.COMPLETE, 0, 0, 0, 2),
            SenderKeyCoveragePolicy.assess(true, 1, 1, listOf("SENT", "sent"), null))
        assertEquals(Assessment(false, Reason.COMPLETE, 0, 0, 0, 1),
            SenderKeyCoveragePolicy.assess(true, 1, 1, listOf("ſENT"), null))
        assertEquals(Assessment(true, Reason.FAILED_TARGETS, 1, 0, 0, 1),
            SenderKeyCoveragePolicy.assess(true, 1, 1, listOf("ſENT", "FAILED"), null))
        assertEquals(Assessment(true, Reason.PENDING_TARGETS, 0, 1, 1, 0),
            SenderKeyCoveragePolicy.assess(true, 1, 1, listOf("PENDING", "UNKNOWN"), 5))
        // Priority is failed > pending > unknown; counts are exact.
        assertEquals(Assessment(true, Reason.FAILED_TARGETS, 1, 1, 1, 1),
            SenderKeyCoveragePolicy.assess(true, 1, 1, listOf("FAILED", "PENDING", "x", "SENT"), null))
        assertEquals(Assessment(true, Reason.UNKNOWN_TARGETS, 0, 0, 1, 0),
            SenderKeyCoveragePolicy.assess(true, 1, 1, listOf("SENT "), null))
        // "pendİng" (dotted capital I) IS PENDING: Character.toLowerCase
        // maps İ to 'i', so it folds to 'I' under equals(ignoreCase).
        assertEquals(Assessment(true, Reason.PENDING_TARGETS, 0, 1, 0, 0),
            SenderKeyCoveragePolicy.assess(true, 1, 1, listOf("pend\u0130ng"), null))
        // requiresDistribution is the assess projection.
        assertEquals(true, SenderKeyCoveragePolicy.requiresDistribution(false, 1, 1, emptyList()))
        assertEquals(false, SenderKeyCoveragePolicy.requiresDistribution(true, 1, 1, listOf("SENT")))
        assertEquals(true, SenderKeyCoveragePolicy.requiresDistribution(true, 1, 1, listOf("FAILED")))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle128.genSkc consumption order exactly, so seed
        // 128002 replays the same 1500 GEN lines drive128.py ran.
        val rnd = Random(128002)
        repeat(1500) {
            val op = rnd.nextInt(2)
            val hasLocal = genSkcBool(rnd)
            val reqEpoch = genSkcEpoch(rnd)
            val statusEpoch = genSkcEpoch(rnd)
            val targets = genSkcTargetList(rnd)
            val total = genSkcTotal(rnd)
            if (op == 0) {
                val want = skcAssessOracle(hasLocal, reqEpoch, statusEpoch, targets, total)
                val got = SenderKeyCoveragePolicy.assess(hasLocal, reqEpoch, statusEpoch, targets, total)
                assertEquals(want.requiresDistribution, got.requiresDistribution)
                assertEquals(want.reason, got.reason.name)
                assertEquals(want.failedCount, got.failedCount)
                assertEquals(want.pendingCount, got.pendingCount)
                assertEquals(want.unknownCount, got.unknownCount)
                assertEquals(want.sentCount, got.sentCount)
            } else {
                assertEquals(
                    skcRequiresOracle(hasLocal, reqEpoch, statusEpoch, targets),
                    SenderKeyCoveragePolicy.requiresDistribution(hasLocal, reqEpoch, statusEpoch, targets)
                )
            }
        }
    }

    @Test
    fun sweepExhaustive() {
        // Epoch cross product, the status universe exhaustively over
        // lists of 0-2, and the reported-total set -- the same inputs
        // drive128.py sweeps.
        val uniStatus = arrayOf("SENT", "sent", "FAILED", "PENDING", "UNKNOWN", "")
        val uniEpochs = longArrayOf(Long.MIN_VALUE, -1, 0, 1, 2, 5, 100, Long.MAX_VALUE)
        val totals = arrayOf<Int?>(null, -1, 0, 1, 2, 5)
        val targetLists = ArrayList<List<String>>()
        targetLists.add(emptyList())
        for (s in uniStatus) targetLists.add(listOf(s))
        for (a in uniStatus) for (b in uniStatus) targetLists.add(listOf(a, b))
        for (hasLocal in listOf(false, true)) {
            for (req in uniEpochs) {
                for (status in uniEpochs) {
                    for (targets in targetLists) {
                        for (total in totals) {
                            val want = skcAssessOracle(hasLocal, req, status, targets, total)
                            val got = SenderKeyCoveragePolicy.assess(hasLocal, req, status, targets, total)
                            assertEquals(want.requiresDistribution, got.requiresDistribution)
                            assertEquals(want.reason, got.reason.name)
                            assertEquals(want.failedCount, got.failedCount)
                            assertEquals(want.pendingCount, got.pendingCount)
                            assertEquals(want.unknownCount, got.unknownCount)
                            assertEquals(want.sentCount, got.sentCount)
                        }
                        assertEquals(
                            skcRequiresOracle(hasLocal, req, status, targets),
                            SenderKeyCoveragePolicy.requiresDistribution(hasLocal, req, status, targets)
                        )
                    }
                }
            }
        }
        for (s in SKC_STATUS_POOL) {
            val want = skcAssessOracle(true, 1, 1, listOf(s), null)
            val got = SenderKeyCoveragePolicy.assess(true, 1, 1, listOf(s), null)
            assertEquals(want.requiresDistribution, got.requiresDistribution)
            assertEquals(want.reason, got.reason.name)
        }
        for (s in arrayOf("\u2c2f", "\u2c5f", "\ua7c0", "\ua7c1", "ſ", "\u212a",
            "K", "k", "S", "s", "İ", "ı", "I", "i", "ß")) {
            val want = skcAssessOracle(true, 1, 1, listOf(s), null)
            val got = SenderKeyCoveragePolicy.assess(true, 1, 1, listOf(s), null)
            assertEquals(want.requiresDistribution, got.requiresDistribution)
            assertEquals(want.reason, got.reason.name)
        }
    }
}
