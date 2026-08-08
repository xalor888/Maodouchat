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
}
