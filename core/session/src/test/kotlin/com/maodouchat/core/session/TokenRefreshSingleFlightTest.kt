package com.maodouchat.core.session

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenRefreshSingleFlightTest {

    @Test
    fun `concurrent 401 only triggers one refresh`() = runBlocking {
        var invocations = 0
        val singleFlight = TokenRefreshSingleFlight(
            onRefresh = {
                invocations++
                delay(50)
                true
            },
            successCooldownMillis = 10_000L,
        )
        val results = (1..5).map { async { singleFlight.refresh() } }.awaitAll()
        assertEquals(1, invocations)
        assertEquals(List(5) { true }, results)
    }

    @Test
    fun `failure is not cached and is retried`() = runBlocking {
        var invocations = 0
        val singleFlight = TokenRefreshSingleFlight(
            onRefresh = {
                invocations++
                invocations > 1 // 第一次失败，第二次成功
            },
            successCooldownMillis = 10_000L,
        )
        assertFalse(singleFlight.refresh(nowMillis = 0L))
        assertTrue(singleFlight.refresh(nowMillis = 0L))
        assertEquals(2, invocations)
    }

    @Test
    fun `cooldown expiry triggers a fresh refresh`() = runBlocking {
        var invocations = 0
        val singleFlight = TokenRefreshSingleFlight(
            onRefresh = { invocations++; true },
            successCooldownMillis = 1_000L,
        )
        assertTrue(singleFlight.refresh(nowMillis = 0L))
        assertTrue(singleFlight.refresh(nowMillis = 2_000L))
        assertEquals(2, invocations)
    }
}
