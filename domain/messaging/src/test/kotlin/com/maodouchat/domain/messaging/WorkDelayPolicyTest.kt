package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkDelayPolicyTest {

    @Test
    fun `future trigger keeps the exact delay`() {
        assertEquals(60_000L, WorkDelayPolicy.initialDelayUntil(61_000L, 1_000L))
    }

    @Test
    fun `past or near trigger floors at one second`() {
        assertEquals(1_000L, WorkDelayPolicy.initialDelayUntil(500L, 1_000L))
        assertEquals(1_000L, WorkDelayPolicy.initialDelayUntil(1_000L, 1_000L))
        assertEquals(1_000L, WorkDelayPolicy.initialDelayUntil(1_500L, 1_000L))
    }
}
