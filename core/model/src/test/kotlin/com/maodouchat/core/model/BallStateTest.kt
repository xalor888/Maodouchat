package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BallStateTest {

    @Test
    fun `entries keep declaration order`() {
        assertEquals(listOf(BallState.IDLE, BallState.RUNNING, BallState.PENDING_APPROVAL), BallState.entries.toList())
    }

    @Test
    fun `valueOf round-trips every entry`() {
        BallState.entries.forEach { assertEquals(it, BallState.valueOf(it.name)) }
    }
}
