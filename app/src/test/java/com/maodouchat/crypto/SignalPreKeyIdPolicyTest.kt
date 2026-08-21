package com.maodouchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalPreKeyIdPolicyTest {

    @Test
    fun `valid ids stay inside signal 24-bit range`() {
        assertTrue(SignalPreKeyIdPolicy.isValid(1))
        assertTrue(SignalPreKeyIdPolicy.isValid(SignalPreKeyIdPolicy.MAX_ID))
        assertFalse(SignalPreKeyIdPolicy.isValid(0))
        assertFalse(SignalPreKeyIdPolicy.isValid(-1))
        assertFalse(SignalPreKeyIdPolicy.isValid(SignalPreKeyIdPolicy.MAX_ID + 1))
        assertFalse(SignalPreKeyIdPolicy.isValid(Int.MAX_VALUE))
    }

    @Test
    fun `signed prekey id never uses Int MAX_VALUE domain`() {
        val id = SignalPreKeyIdPolicy.randomSignedPreKeyId { n ->
            assertEquals(SignalPreKeyIdPolicy.MAX_ID, n)
            n - 1
        }
        assertEquals(SignalPreKeyIdPolicy.MAX_ID, id)
        assertTrue(SignalPreKeyIdPolicy.isValid(id))

        val min = SignalPreKeyIdPolicy.randomSignedPreKeyId { 0 }
        assertEquals(1, min)
    }

    @Test
    fun `batch start continues after existing ids without overflowing`() {
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = 100,
            count = 50,
            randomIntExclusive = { error("random must not be used when sequential fits") },
        )
        assertEquals(101, start)
        assertTrue(start + 50 - 1 <= SignalPreKeyIdPolicy.MAX_ID)
    }

    @Test
    fun `batch start wraps when remaining space is too small`() {
        val maxId = SignalPreKeyIdPolicy.MAX_ID
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = maxId - 10,
            count = 50,
            randomIntExclusive = { n ->
                assertEquals(maxId - 50 + 1, n)
                0
            },
        )
        assertEquals(1, start)
        assertTrue(start + 50 - 1 <= maxId)
    }

    @Test
    fun `empty store starts at 1`() {
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = 0,
            count = 50,
            randomIntExclusive = { error("unused") },
        )
        assertEquals(1, start)
    }
}
