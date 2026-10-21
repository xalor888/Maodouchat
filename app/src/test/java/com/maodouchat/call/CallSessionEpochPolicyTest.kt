package com.maodouchat.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSessionEpochPolicyTest {
    @Test
    fun `hang-up under current epoch then bump is safe`() {
        assertTrue(
            CallSessionEpochPolicy.isHangUpThenInvalidateSafe(
                hangUpGeneration = 3L,
                currentGenerationAtHangUp = 3L,
                postInvalidateGeneration = 4L,
            )
        )
    }

    @Test
    fun `hang-up after early invalidate mismatches active epoch`() {
        // Early invalidate bumps to 4, hang-up stamps 4, but live VM still on 3 → drop.
        assertFalse(
            CallSessionEpochPolicy.isHangUpThenInvalidateSafe(
                hangUpGeneration = 4L,
                currentGenerationAtHangUp = 3L,
                postInvalidateGeneration = 4L,
            )
        )
    }

    @Test
    fun `no post-invalidate leaves hang-up valid for next account`() {
        assertFalse(
            CallSessionEpochPolicy.isHangUpThenInvalidateSafe(
                hangUpGeneration = 3L,
                currentGenerationAtHangUp = 3L,
                postInvalidateGeneration = 3L,
            )
        )
    }

    @Test
    fun `stale hang-up stamped under previous epoch is dropped`() {
        // 挂断盖的是旧 epoch 的戳，活 VM 已到新 epoch：丢弃。
        assertFalse(
            CallSessionEpochPolicy.isHangUpThenInvalidateSafe(
                hangUpGeneration = 2L,
                currentGenerationAtHangUp = 3L,
                postInvalidateGeneration = 4L,
            )
        )
    }

    @Test
    fun `post-invalidate jumping two generations is still safe`() {
        assertTrue(
            CallSessionEpochPolicy.isHangUpThenInvalidateSafe(
                hangUpGeneration = 3L,
                currentGenerationAtHangUp = 3L,
                postInvalidateGeneration = 5L,
            )
        )
    }

    @Test
    fun `post-invalidate behind hang-up is unsafe`() {
        assertFalse(
            CallSessionEpochPolicy.isHangUpThenInvalidateSafe(
                hangUpGeneration = 5L,
                currentGenerationAtHangUp = 5L,
                postInvalidateGeneration = 4L,
            )
        )
    }

    @Test
    fun `zero epoch handshake follows the same rule`() {
        assertTrue(
            CallSessionEpochPolicy.isHangUpThenInvalidateSafe(
                hangUpGeneration = 0L,
                currentGenerationAtHangUp = 0L,
                postInvalidateGeneration = 1L,
            )
        )
        assertFalse(
            CallSessionEpochPolicy.isHangUpThenInvalidateSafe(
                hangUpGeneration = 0L,
                currentGenerationAtHangUp = 0L,
                postInvalidateGeneration = 0L,
            )
        )
    }

    @Test
    fun `decision is deterministic across repeated calls`() {
        repeat(3) {
            assertTrue(
                CallSessionEpochPolicy.isHangUpThenInvalidateSafe(
                    hangUpGeneration = 7L,
                    currentGenerationAtHangUp = 7L,
                    postInvalidateGeneration = 8L,
                )
            )
        }
    }
}
