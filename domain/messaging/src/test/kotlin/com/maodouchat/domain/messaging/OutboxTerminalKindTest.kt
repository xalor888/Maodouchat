package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessagingV2EventAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutboxTerminalKindTest {

    @Test
    fun `delete and revoke map to tombstone kinds`() {
        assertEquals("DELETE", terminalTombstoneKind(MessagingV2EventAction.DELETE))
        assertEquals("REVOKE", terminalTombstoneKind(MessagingV2EventAction.REVOKE))
    }

    @Test
    fun `other actions write no tombstone`() {
        assertNull(terminalTombstoneKind(MessagingV2EventAction.EDIT))
        assertNull(terminalTombstoneKind(MessagingV2EventAction.REACTION_SET))
        assertNull(terminalTombstoneKind(MessagingV2EventAction.REACTION_SNAPSHOT))
        assertNull(terminalTombstoneKind(MessagingV2EventAction.DELIVERY_RECEIPT))
        assertNull(terminalTombstoneKind(MessagingV2EventAction.READ_RECEIPT))
        assertNull(terminalTombstoneKind(MessagingV2EventAction.PLAY_RECEIPT))
        assertNull(terminalTombstoneKind("UNKNOWN"))
    }
}
