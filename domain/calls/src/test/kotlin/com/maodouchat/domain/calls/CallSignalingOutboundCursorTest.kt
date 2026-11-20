package com.maodouchat.domain.calls

import kotlin.test.Test
import kotlin.test.assertEquals

class CallSignalingOutboundCursorTest {

    @Test
    fun `begin resets sequence and builds stable idempotency keys`() {
        val cursor = CallSignalingOutboundCursor()
        cursor.begin(epochSeed = 42L)
        val first = cursor.next("call-a", "OFFER")
        val second = cursor.next("call-a", "ice-candidate")
        assertEquals(42L, first.epoch)
        assertEquals(1L, first.sequence)
        assertEquals("call-a|42|1|offer", first.idempotencyKey)
        assertEquals(42L, second.epoch)
        assertEquals(2L, second.sequence)
        assertEquals("call-a|42|2|ice-candidate", second.idempotencyKey)
    }
}
