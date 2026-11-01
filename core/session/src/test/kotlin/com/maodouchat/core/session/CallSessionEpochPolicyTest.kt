package com.maodouchat.core.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSessionEpochPolicyTest {

    @Test
    fun hangUpUnderCurrentEpochThenBumpIsSafe() {
        assertTrue(
            CallSessionEpochPolicy.isHangUpThenInvalidateSafe(
                hangUpGeneration = 3L,
                currentGenerationAtHangUp = 3L,
                postInvalidateGeneration = 4L,
            )
        )
    }

    @Test
    fun mismatchedHangUpIsUnsafe() {
        assertFalse(
            CallSessionEpochPolicy.isHangUpThenInvalidateSafe(
                hangUpGeneration = 4L,
                currentGenerationAtHangUp = 3L,
                postInvalidateGeneration = 4L,
            )
        )
    }

    @Test
    fun noPostBumpLeavesHangUpValidForNextAccount() {
        assertFalse(
            CallSessionEpochPolicy.isHangUpThenInvalidateSafe(
                hangUpGeneration = 3L,
                currentGenerationAtHangUp = 3L,
                postInvalidateGeneration = 3L,
            )
        )
    }
}
