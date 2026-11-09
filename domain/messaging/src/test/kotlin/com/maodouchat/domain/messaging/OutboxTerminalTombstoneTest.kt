package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutboxTerminalTombstoneTest {

    @Test
    fun `revoke event lands a tombstone on its target`() {
        assertEquals(
            TerminalTombstone(targetMessageId = "msg-1", kind = "REVOKE"),
            terminalTombstone("REVOKE", "msg-1"),
        )
    }

    @Test
    fun `delete event lands a tombstone on its target`() {
        assertEquals(
            TerminalTombstone(targetMessageId = "msg-1", kind = "DELETE"),
            terminalTombstone("DELETE", "msg-1"),
        )
    }

    @Test
    fun `edit event lands no tombstone`() {
        assertNull(terminalTombstone("EDIT", "msg-1"))
    }

    @Test
    fun `receipt event lands no tombstone`() {
        assertNull(terminalTombstone("READ_RECEIPT", "msg-1"))
    }
}
