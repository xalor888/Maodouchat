// Fuzz / random-oracle tests for ModerationRuleSettingsPolicy
// (checklist line 931, 135th block). Test-only, zero production changes.
// The oracle is hand-written from the production source: hit threshold
// clamps to 1..10000, window minutes clamp to 1..43200 then multiply by
// 60000 ms. No ModerationRuleSettingsPolicy fuzz test existed before
// this block: content-grep dedup 0 hits on all remote refactor
// branches, main's FuzzTest files, and all open PR titles/bodies,
// except the bare name `windowMs` hitting two same-named local oracle
// parameters (fuzz-130 ChatTimelineModelFuzzTest, fuzz-40
// MessageTimelineGroupingPolicyFuzzTest) -- neither calls this policy;
// PR #2137 only records fuzz-101 dropping this cluster over that
// same bare-name collision. That is a mention, not coverage.
package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

private fun mrsClampOracle(hitThreshold: Int): Int =
    hitThreshold.coerceIn(1, 10000)

private fun mrsWindowOracle(windowMinutes: Long): Long =
    windowMinutes.coerceIn(1L, 43200L) * 60000L

// ---------- generators (mirrored by Oracle135 GEN, seeds 135001/135002) ----------

private val MRS_INT_POOL = intArrayOf(
    Int.MIN_VALUE, Int.MIN_VALUE + 1, -1000, -2, -1, 0, 1, 2,
    9998, 9999, 10000, 10001, 10002, 100000, Int.MAX_VALUE - 1, Int.MAX_VALUE
)

private val MRS_LONG_POOL = longArrayOf(
    Long.MIN_VALUE, Long.MIN_VALUE + 1, -1000L, -2L, -1L, 0L, 1L, 2L,
    43198L, 43199L, 43200L, 43201L, 43202L, 100000L, Long.MAX_VALUE - 1, Long.MAX_VALUE
)

private fun genMrsInt(rnd: Random): Int =
    if (rnd.nextInt(2) == 0) MRS_INT_POOL[rnd.nextInt(MRS_INT_POOL.size)] else rnd.nextInt()

private fun genMrsLong(rnd: Random): Long =
    if (rnd.nextInt(2) == 0) MRS_LONG_POOL[rnd.nextInt(MRS_LONG_POOL.size)] else rnd.nextLong()

class ModerationRuleSettingsPolicyFuzzTest {

    @Test
    fun pinnedBoundaries() {
        // Clamp edges: below 1 floors to 1, above 10000 caps at 10000.
        assertEquals(1, ModerationRuleSettingsPolicy.clampHitThreshold(Int.MIN_VALUE))
        assertEquals(1, ModerationRuleSettingsPolicy.clampHitThreshold(Int.MIN_VALUE + 1))
        assertEquals(1, ModerationRuleSettingsPolicy.clampHitThreshold(-1000))
        assertEquals(1, ModerationRuleSettingsPolicy.clampHitThreshold(-1))
        assertEquals(1, ModerationRuleSettingsPolicy.clampHitThreshold(0))
        assertEquals(1, ModerationRuleSettingsPolicy.clampHitThreshold(1))
        assertEquals(2, ModerationRuleSettingsPolicy.clampHitThreshold(2))
        assertEquals(500, ModerationRuleSettingsPolicy.clampHitThreshold(500))
        assertEquals(9999, ModerationRuleSettingsPolicy.clampHitThreshold(9999))
        assertEquals(10000, ModerationRuleSettingsPolicy.clampHitThreshold(10000))
        assertEquals(10000, ModerationRuleSettingsPolicy.clampHitThreshold(10001))
        assertEquals(10000, ModerationRuleSettingsPolicy.clampHitThreshold(10002))
        assertEquals(10000, ModerationRuleSettingsPolicy.clampHitThreshold(100000))
        assertEquals(10000, ModerationRuleSettingsPolicy.clampHitThreshold(Int.MAX_VALUE - 1))
        assertEquals(10000, ModerationRuleSettingsPolicy.clampHitThreshold(Int.MAX_VALUE))
        // Window edges: clamped minutes times 60000; 43200 minutes is 30 days.
        assertEquals(60000L, ModerationRuleSettingsPolicy.windowMs(Long.MIN_VALUE))
        assertEquals(60000L, ModerationRuleSettingsPolicy.windowMs(-1000L))
        assertEquals(60000L, ModerationRuleSettingsPolicy.windowMs(-1L))
        assertEquals(60000L, ModerationRuleSettingsPolicy.windowMs(0L))
        assertEquals(60000L, ModerationRuleSettingsPolicy.windowMs(1L))
        assertEquals(120000L, ModerationRuleSettingsPolicy.windowMs(2L))
        assertEquals(3600000L, ModerationRuleSettingsPolicy.windowMs(60L))
        assertEquals(43199L * 60000L, ModerationRuleSettingsPolicy.windowMs(43199L))
        assertEquals(43200L * 60000L, ModerationRuleSettingsPolicy.windowMs(43200L))
        assertEquals(43200L * 60000L, ModerationRuleSettingsPolicy.windowMs(43201L))
        assertEquals(43200L * 60000L, ModerationRuleSettingsPolicy.windowMs(43202L))
        assertEquals(43200L * 60000L, ModerationRuleSettingsPolicy.windowMs(100000L))
        assertEquals(43200L * 60000L, ModerationRuleSettingsPolicy.windowMs(Long.MAX_VALUE - 1))
        assertEquals(43200L * 60000L, ModerationRuleSettingsPolicy.windowMs(Long.MAX_VALUE))
    }

    @Test
    fun randomOracleMatchesProduction() {
        // Mirrors Oracle135.genMrsClamp consumption order exactly, so seed
        // 135001 replays the same 1500 GEN lines drive135.py ran.
        val rnd = Random(135001)
        repeat(1500) {
            val v = genMrsInt(rnd)
            assertEquals(mrsClampOracle(v), ModerationRuleSettingsPolicy.clampHitThreshold(v))
        }
        // Same for the window leg, seed 135002.
        val rnd2 = Random(135002)
        repeat(1500) {
            val v = genMrsLong(rnd2)
            assertEquals(mrsWindowOracle(v), ModerationRuleSettingsPolicy.windowMs(v))
        }
    }

    @Test
    fun sweepExhaustive() {
        // Every pool value, then dense grids across both clamp edges.
        for (v in MRS_INT_POOL) {
            assertEquals(mrsClampOracle(v), ModerationRuleSettingsPolicy.clampHitThreshold(v))
        }
        for (v in -10..10010) {
            assertEquals(mrsClampOracle(v), ModerationRuleSettingsPolicy.clampHitThreshold(v))
        }
        for (v in MRS_LONG_POOL) {
            assertEquals(mrsWindowOracle(v), ModerationRuleSettingsPolicy.windowMs(v))
        }
        for (v in -10L..43210L) {
            assertEquals(mrsWindowOracle(v), ModerationRuleSettingsPolicy.windowMs(v))
        }
    }
}
