package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 未接来电保留窗口 fuzz（清单 931 行第二十二块）。
 * 只加测试、零生产改动：`MissedCallRetention.kt` 的 `missedCallRetentionCutoffMs`。
 * 固定种子，同一份输入每次跑出同一批用例。
 */
class MissedCallRetentionFuzzTest {

    // 本函数只有一行减法：oracle 与生产侧同式，测的是 28 天常量与回绕行为被锁定。
    private fun oracleCutoffMs(nowMs: Long): Long = nowMs - MISSED_CALL_RETENTION_MS

    @Test
    fun `cutoff matches oracle on random timestamps`() {
        val r = Random(0x2E7A_0622L)
        repeat(200) {
            val nowMs = when (r.nextInt(6)) {
                0 -> Long.MIN_VALUE
                1 -> Long.MAX_VALUE
                2 -> 0L
                3 -> MISSED_CALL_RETENTION_MS
                4 -> r.nextLong(0L, 2_000_000_000_000L)
                else -> r.nextLong()
            }
            assertEquals(oracleCutoffMs(nowMs), missedCallRetentionCutoffMs(nowMs))
        }
    }

    @Test
    fun `retention window is 28 days`() {
        assertEquals(28L * 24L * 60L * 60L * 1000L, MISSED_CALL_RETENTION_MS)
        assertEquals(2_419_200_000L, MISSED_CALL_RETENTION_MS)
        assertEquals(1697580800000L, missedCallRetentionCutoffMs(1700000000000L))
        assertEquals(0L, missedCallRetentionCutoffMs(MISSED_CALL_RETENTION_MS))
        // 负时间戳同样只做减法。
        assertEquals(-MISSED_CALL_RETENTION_MS, missedCallRetentionCutoffMs(0L))
    }

    @Test
    fun `cutoff wraps on Long extremes`() {
        // Long.MIN_VALUE 减窗口会下溢回绕：与 oracle 同值即行为一致。
        assertEquals(Long.MIN_VALUE - MISSED_CALL_RETENTION_MS, missedCallRetentionCutoffMs(Long.MIN_VALUE))
        assertEquals(Long.MAX_VALUE - MISSED_CALL_RETENTION_MS, missedCallRetentionCutoffMs(Long.MAX_VALUE))
    }
}
