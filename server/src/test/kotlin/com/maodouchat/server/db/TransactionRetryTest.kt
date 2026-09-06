package com.maodouchat.server.db

import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransactionRetryTest {

    @Test
    fun `deadlock fails twice then succeeds`() {
        val attempts = AtomicInteger()
        val result = withSerializationRetry(maxAttempts = 3) {
            if (attempts.incrementAndGet() < 3) {
                throw SQLException("deadlock detected", "40P01")
            }
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, attempts.get())
    }

    @Test
    fun `h2 serialization state is retryable`() {
        assertTrue(isSerializationFailure(SQLException("timeout", "40001")))
        assertTrue(isSerializationFailure(RuntimeException(SQLException("wrapped", "40P01"))))
    }

    @Test
    fun `unique violation is not retryable`() {
        val attempts = AtomicInteger()
        assertFailsWith<SQLException> {
            withSerializationRetry(maxAttempts = 3) {
                attempts.incrementAndGet()
                throw SQLException("duplicate key", "23505")
            }
        }
        assertEquals(1, attempts.get())
    }

    @Test
    fun `exhausted attempts rethrow`() {
        val attempts = AtomicInteger()
        assertFailsWith<SQLException> {
            withSerializationRetry(maxAttempts = 2) {
                attempts.incrementAndGet()
                throw SQLException("deadlock detected", "40P01")
            }
        }
        assertEquals(2, attempts.get())
        assertFalse(isSerializationFailure(IllegalStateException("plain")))
    }
}
