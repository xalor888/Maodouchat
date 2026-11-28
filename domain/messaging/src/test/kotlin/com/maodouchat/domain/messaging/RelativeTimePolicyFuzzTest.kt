package com.maodouchat.domain.messaging

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelativeTimePolicyFuzzTest {

    private val random = Random(0x1A71_2026L)

    // 安全域：|值| < 2^62，now - timestamp 与取反都不会回绕，
    // 此时 BigInteger 精确算术与 Long 算术一致，可作独立 oracle。
    private val safeBound = 1L shl 62

    private fun randomSafeLong(): Long = random.nextLong(-safeBound, safeBound)

    private fun bigOracle(
        timestamp: Long,
        nowMs: Long,
        pastWindowMs: Long,
        futureGraceMs: Long,
    ): Boolean {
        if (timestamp <= 0L) return false
        val delta = BigInteger.valueOf(nowMs).subtract(BigInteger.valueOf(timestamp))
        val past = BigInteger.valueOf(pastWindowMs)
        if (delta >= BigInteger.ZERO && delta < past) return true
        val future = BigInteger.valueOf(futureGraceMs)
        return delta < BigInteger.ZERO && delta.negate() < future
    }

    @Test
    fun `random safe-range inputs match BigInteger oracle`() {
        val windows = listOf(0L, 1L, 60_000L, 120_000L, Long.MAX_VALUE, 7_000L, 500L)
        repeat(200) {
            val timestamp = random.nextLong(1L, safeBound)
            val nowMs = randomSafeLong()
            val past = windows.random(random)
            val future = windows.random(random)
            assertEquals(
                bigOracle(timestamp, nowMs, past, future),
                RelativeTimePolicy.shouldUseJustNow(timestamp, nowMs, past, future),
                "timestamp=$timestamp nowMs=$nowMs past=$past future=$future",
            )
        }
    }

    @Test
    fun `random safe-range inputs match BigInteger oracle with default windows`() {
        repeat(200) {
            val timestamp = random.nextLong(1L, safeBound)
            val nowMs = randomSafeLong()
            assertEquals(
                bigOracle(
                    timestamp,
                    nowMs,
                    RelativeTimePolicy.PAST_JUST_NOW_MS,
                    RelativeTimePolicy.FUTURE_GRACE_MS,
                ),
                RelativeTimePolicy.shouldUseJustNow(timestamp, nowMs),
                "timestamp=$timestamp nowMs=$nowMs",
            )
        }
    }

    @Test
    fun `wraparound behavior is pinned as-is`() {
        // 以下三例的数学真值都是 false（时间戳在极遥远的未来），但 Long
        // 减法回绕后生产实现返回 true；这里钉住当前行为，不当 bug 修：
        // 钉住点由 Python 迷你模型（int64 回绕算术）事先算出。
        // delta = -1 - MAX 回绕为 MIN，-delta 回绕仍为 MIN < 120_000。
        assertTrue(RelativeTimePolicy.shouldUseJustNow(Long.MAX_VALUE, -1L))
        // delta = MIN - MAX 回绕为 1，落入过去窗口。
        assertTrue(RelativeTimePolicy.shouldUseJustNow(Long.MAX_VALUE, Long.MIN_VALUE))
        // delta = (MIN + 1) - 1 恰为 MIN，-delta 回绕仍为 MIN < 120_000。
        assertTrue(RelativeTimePolicy.shouldUseJustNow(1L, Long.MIN_VALUE + 1L))
        // 回绕后仍为 false 的两例：delta 回绕为 MAX（过去分支够不到），
        // 以及 delta 恰为 -MAX（-delta = MAX，不小于 120_000）。
        assertFalse(RelativeTimePolicy.shouldUseJustNow(1L, Long.MIN_VALUE))
        assertFalse(RelativeTimePolicy.shouldUseJustNow(Long.MAX_VALUE, 0L))
    }

    @Test
    fun `window boundaries are exact`() {
        val base = 1_000_000L
        // 过去分支：[0, 60_000)，左闭右开
        assertTrue(RelativeTimePolicy.shouldUseJustNow(base, base))
        assertTrue(RelativeTimePolicy.shouldUseJustNow(base, base + 59_999L))
        assertFalse(RelativeTimePolicy.shouldUseJustNow(base, base + 60_000L))
        // 未来分支：-delta < 120_000，严格小于
        assertTrue(RelativeTimePolicy.shouldUseJustNow(base, base - 119_999L))
        assertFalse(RelativeTimePolicy.shouldUseJustNow(base, base - 120_000L))
        // timestamp 边界：0 与负数恒 false
        assertFalse(RelativeTimePolicy.shouldUseJustNow(0L, base))
        assertFalse(RelativeTimePolicy.shouldUseJustNow(-1L, base))
        assertTrue(RelativeTimePolicy.shouldUseJustNow(1L, 1L))
    }

    @Test
    fun `non-positive windows never match`() {
        val windows = listOf(0L, -1L, -60_000L, Long.MIN_VALUE)
        repeat(100) {
            val timestamp = random.nextLong(1L, safeBound)
            val nowMs = randomSafeLong()
            val window = windows.random(random)
            assertFalse(
                RelativeTimePolicy.shouldUseJustNow(timestamp, nowMs, window, window),
                "timestamp=$timestamp nowMs=$nowMs window=$window",
            )
        }
    }

    @Test
    fun `non-positive timestamp never matches whatever the clock says`() {
        repeat(100) {
            val timestamp = random.nextLong(Long.MIN_VALUE, 1L)
            val nowMs = random.nextLong()
            assertFalse(
                RelativeTimePolicy.shouldUseJustNow(timestamp, nowMs),
                "timestamp=$timestamp nowMs=$nowMs",
            )
        }
    }

    @Test
    fun `default windows are the documented constants`() {
        assertEquals(60_000L, RelativeTimePolicy.PAST_JUST_NOW_MS)
        assertEquals(120_000L, RelativeTimePolicy.FUTURE_GRACE_MS)
    }
}
