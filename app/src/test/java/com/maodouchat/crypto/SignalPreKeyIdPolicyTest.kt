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


    @Test
    fun `count one on empty store stays at one`() {
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = 0,
            count = 1,
            randomIntExclusive = { error("sequential fits") },
        )
        assertEquals(1, start)
    }

    @Test
    fun `count one with negative existing id goes random at top`() {
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = -1,
            count = 1,
            randomIntExclusive = { n ->
                assertEquals(SignalPreKeyIdPolicy.MAX_ID, n)
                n - 1
            },
        )
        assertEquals(SignalPreKeyIdPolicy.MAX_ID, start)
    }

    @Test
    fun `huge count with non-zero existing id also collapses max start`() {
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = 5,
            count = SignalPreKeyIdPolicy.MAX_ID * 2,
            randomIntExclusive = { n ->
                assertEquals(1, n)
                0
            },
        )
        assertEquals(1, start)
    }

    @Test
    fun `random signed prekey id at zero maps to one`() {
        val id = SignalPreKeyIdPolicy.randomSignedPreKeyId { 0 }
        assertEquals(1, id)
        assertTrue(SignalPreKeyIdPolicy.isValid(id))
    }

    @Test
    fun `huge count on empty store keeps sequential start without random`() {
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = 0,
            count = Int.MAX_VALUE,
            randomIntExclusive = { error("sequential fits on empty store") },
        )
        assertEquals(1, start)
    }

    @Test
    fun `nearly full store leaves a two-slot random domain`() {
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = SignalPreKeyIdPolicy.MAX_ID - 1,
            count = SignalPreKeyIdPolicy.MAX_ID - 1,
            randomIntExclusive = { n ->
                assertEquals(2, n)
                1
            },
        )
        assertEquals(2, start)
    }

    @Test
    fun `negative existing id with zero count coerces before falling back to random`() {
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = -3,
            count = 0,
            randomIntExclusive = { n ->
                assertEquals(SignalPreKeyIdPolicy.MAX_ID, n)
                7
            },
        )
        assertEquals(8, start)
        assertTrue(SignalPreKeyIdPolicy.isValid(start))
    }

    @Test
    fun `random signed prekey id near top maps to max minus one`() {
        val id = SignalPreKeyIdPolicy.randomSignedPreKeyId { n ->
            assertEquals(SignalPreKeyIdPolicy.MAX_ID, n)
            n - 2
        }
        assertEquals(SignalPreKeyIdPolicy.MAX_ID - 1, id)
        assertTrue(SignalPreKeyIdPolicy.isValid(id))
    }

    @Test
    fun `overflowed max existing id falls back to random`() {
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = Int.MAX_VALUE,
            count = 1,
            randomIntExclusive = { n ->
                assertEquals(SignalPreKeyIdPolicy.MAX_ID, n)
                0
            },
        )
        assertEquals(1, start)
        assertTrue(SignalPreKeyIdPolicy.isValid(start))
    }

    @Test
    fun `sequential start just below max start boundary fits`() {
        val maxStart = SignalPreKeyIdPolicy.MAX_ID - 10 + 1
        val start = SignalPreKeyIdPolicy.nextBatchStartId(
            maxExistingId = maxStart - 1,
            count = 10,
            randomIntExclusive = { error("sequential fits") },
        )
        assertEquals(maxStart, start)
        assertEquals(SignalPreKeyIdPolicy.MAX_ID, start + 10 - 1)
    }
}
