package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

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
