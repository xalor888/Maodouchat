package com.maodouchat.server.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundTaskHealthTest {

    @Test
    fun `unknown tasks do not fail readiness`() {
        val health = BackgroundTaskHealth(clock = { 1_000L })
        assertTrue(health.allHealthy())
        assertTrue(health.degradedTasks().isEmpty())
    }

    @Test
    fun `two failures stay healthy, third degrades`() {
        var now = 1_000L
        val health = BackgroundTaskHealth(clock = { now })
        repeat(2) {
            health.recordFailure("gc", RuntimeException("boom"))
            assertTrue(health.allHealthy())
        }
        health.recordFailure("gc", RuntimeException("boom"))
        assertFalse(health.allHealthy())
        assertEquals(listOf("gc"), health.degradedTasks())
    }

    @Test
    fun `success resets consecutive failures`() {
        var now = 1_000L
        val health = BackgroundTaskHealth(clock = { now })
        repeat(3) { health.recordFailure("gc", RuntimeException("boom")) }
        assertFalse(health.allHealthy())
        now += 1_000L
        health.heartbeat("gc")
        assertTrue(health.allHealthy())
        val status = health.snapshot()["gc"]!!
        assertEquals(0, status.consecutiveFailures)
        assertEquals(now, status.lastSuccessAt)
    }

    @Test
    fun `failure details are captured and bounded`() {
        var now = 1_000L
        val health = BackgroundTaskHealth(clock = { now })
        health.recordFailure("gc", IllegalStateException("x".repeat(1000)))
        val status = health.snapshot()["gc"]!!
        assertEquals(now, status.lastErrorAt)
        assertTrue(status.lastError!!.startsWith("IllegalStateException:"))
        assertTrue(status.lastError!!.length <= 500)
    }

    @Test
    fun `tasks are isolated from each other`() {
        var now = 1_000L
        val health = BackgroundTaskHealth(clock = { now })
        repeat(3) { health.recordFailure("bad", RuntimeException()) }
        health.heartbeat("good")
        assertEquals(listOf("bad"), health.degradedTasks())
    }
}
