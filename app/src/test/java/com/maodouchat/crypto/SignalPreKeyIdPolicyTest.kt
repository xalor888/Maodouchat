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

    @Test
    fun `random id upper edge hits max`() {
        val id = SignalPreKeyIdPolicy.randomSignedPreKeyId { n ->
            assertEquals(SignalPreKeyIdPolicy.MAX_ID, n)
            n - 1
        }
        assertEquals(SignalPreKeyIdPolicy.MAX_ID, id)
        assertTrue(SignalPreKeyIdPolicy.isValid(id))
    }

    @Test
    fun `exact fit sequential start takes boundary`() {
        val maxStart = SignalPreKeyIdPolicy.MAX_ID - 50 + 1
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = maxStart - 1,
            count = 50,
            randomIntExclusive = { error("random must not be used on exact fit") },
        )
        assertEquals(maxStart, start)
        assertTrue(start + 50 - 1 <= SignalPreKeyIdPolicy.MAX_ID)
    }

    @Test
    fun `zero count coerces to one`() {
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = 0,
            count = 0,
            randomIntExclusive = { error("unused") },
        )
        assertEquals(1, start)
    }

    @Test
    fun `negative existing id falls back to random`() {
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = -5,
            count = 50,
            randomIntExclusive = { n ->
                assertEquals(SignalPreKeyIdPolicy.MAX_ID - 50 + 1, n)
                0
            },
        )
        assertEquals(1, start)
    }

    @Test
    fun `count beyond 24-bit collapses max start`() {
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = 0,
            count = SignalPreKeyIdPolicy.MAX_ID + 1,
            randomIntExclusive = { error("unused") },
        )
        assertEquals(1, start)
        assertFalse(SignalPreKeyIdPolicy.isValid(Int.MIN_VALUE))
    }

    @Test
    fun `sequential off by one past max start falls back to random`() {
        val maxStart = SignalPreKeyIdPolicy.MAX_ID - 1
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = SignalPreKeyIdPolicy.MAX_ID - 1,
            count = 2,
            randomIntExclusive = { n ->
                assertEquals(maxStart, n)
                n - 1
            },
        )
        assertEquals(maxStart, start)
        assertEquals(SignalPreKeyIdPolicy.MAX_ID, start + 2 - 1)
    }

    @Test
    fun `random start at top never overflows 24-bit`() {
        val maxStart = SignalPreKeyIdPolicy.MAX_ID - 100 + 1
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = SignalPreKeyIdPolicy.MAX_ID,
            count = 100,
            randomIntExclusive = { n ->
                assertEquals(maxStart, n)
                n - 1
            },
        )
        assertEquals(maxStart, start)
        assertEquals(SignalPreKeyIdPolicy.MAX_ID, start + 100 - 1)
    }

    @Test
    fun `random signed prekey id maps midpoint`() {
        val id = SignalPreKeyIdPolicy.randomSignedPreKeyId { n ->
            assertEquals(SignalPreKeyIdPolicy.MAX_ID, n)
            n / 2
        }
        assertEquals(1 + SignalPreKeyIdPolicy.MAX_ID / 2, id)
        assertTrue(SignalPreKeyIdPolicy.isValid(id))
    }

    @Test
    fun `negative count coerces to one`() {
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = 0,
            count = -7,
            randomIntExclusive = { error("unused") },
        )
        assertEquals(1, start)
    }

    @Test
    fun `full store falls back to random single id`() {
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = SignalPreKeyIdPolicy.MAX_ID,
            count = 1,
            randomIntExclusive = { n ->
                assertEquals(SignalPreKeyIdPolicy.MAX_ID, n)
                n - 1
            },
        )
        assertEquals(SignalPreKeyIdPolicy.MAX_ID, start)
        assertTrue(SignalPreKeyIdPolicy.isValid(start))
    }
}
