package com.maodouchat.domain.calls

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatMissedCallsModelsTest {

    @Test
    fun `call log row keeps fields and copy semantics`() {
        val row = CallLogRow(
            "id1", "peer1", "Alice", true, Direction.OUTGOING,
            State.MISSED, 1700000000000L, 0L
        )
        assertEquals("id1", row.id)
        assertEquals(Direction.OUTGOING, row.direction)
        assertEquals(5000L, row.copy(durationMs = 5000L).durationMs)
    }
}
