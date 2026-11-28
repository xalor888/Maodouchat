package com.maodouchat.domain.calls

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CallActionBusModelsFuzzTest {

    private val random = Random(0xCA11_2026L)

    private fun randomString(): String {
        val pool = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf(' ', '_', '-', '.', 'é', '中', '文')
        return (0 until random.nextInt(0, 12)).map { pool.random(random) }.joinToString("")
    }

    private fun randomLong(): Long =
        when (random.nextInt(5)) {
            0 -> 0L
            1 -> Long.MAX_VALUE
            2 -> Long.MIN_VALUE
            3 -> random.nextLong(-1000, 1000)
            else -> random.nextLong()
        }

    private fun randomHangUpRequest(): HangUpRequest =
        HangUpRequest(callId = randomString(), notifyPeer = random.nextBoolean(), sessionGeneration = randomLong())

    @Test
    fun `hang-up request copy and equality over random fields`() {
        repeat(200) {
            val request = randomHangUpRequest()
            assertEquals(request, request.copy())
            assertEquals(request.hashCode(), request.copy().hashCode())
            assertEquals(request, request.copy(callId = request.callId))
            assertNotEquals(request, request.copy(callId = request.callId + "x"))
            assertNotEquals(request, request.copy(notifyPeer = !request.notifyPeer))
            assertNotEquals(request, request.copy(sessionGeneration = request.sessionGeneration + 1))
        }
    }

    @Test
    fun `notify peer defaults to true`() {
        assertEquals(true, HangUpRequest(callId = "c", sessionGeneration = 3L).notifyPeer)
        assertEquals(false, HangUpRequest(callId = "c", notifyPeer = false, sessionGeneration = 3L).notifyPeer)
    }

    @Test
    fun `session generation extremes are retained`() {
        for (generation in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) {
            val request = HangUpRequest(callId = "c", sessionGeneration = generation)
            assertEquals(generation, request.sessionGeneration)
        }
        repeat(200) {
            val request = randomHangUpRequest()
            assertEquals(request.sessionGeneration, HangUpRequest(request.callId, request.notifyPeer, request.sessionGeneration).sessionGeneration)
        }
    }
}
