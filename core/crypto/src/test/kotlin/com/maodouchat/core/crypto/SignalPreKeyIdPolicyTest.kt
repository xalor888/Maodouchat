package com.maodouchat.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalPreKeyIdPolicyTest {

    @Test
    fun validIdsStayInsideSignal24BitRange() {
        assertTrue(SignalPreKeyIdPolicy.isValid(1))
        assertTrue(SignalPreKeyIdPolicy.isValid(SignalPreKeyIdPolicy.MAX_ID))
        assertFalse(SignalPreKeyIdPolicy.isValid(0))
        assertFalse(SignalPreKeyIdPolicy.isValid(SignalPreKeyIdPolicy.MAX_ID + 1))
    }

    @Test
    fun batchStartContinuesAfterExistingIds() {
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = 100,
            count = 50,
            randomIntExclusive = { error("random must not be used when sequential fits") },
        )
        assertEquals(101, start)
    }

    @Test
    fun batchStartWrapsWhenRemainingSpaceIsTooSmall() {
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = SignalPreKeyIdPolicy.MAX_ID - 10,
            count = 50,
            randomIntExclusive = { 0 },
        )
        assertEquals(1, start)
    }

    @Test
    fun signedPreKeyIdMapsIntoRange() {
        val id = SignalPreKeyIdPolicy.randomSignedPreKeyId { it - 1 }
        assertEquals(SignalPreKeyIdPolicy.MAX_ID, id)
        assertTrue(SignalPreKeyIdPolicy.isValid(id))
    }
}
