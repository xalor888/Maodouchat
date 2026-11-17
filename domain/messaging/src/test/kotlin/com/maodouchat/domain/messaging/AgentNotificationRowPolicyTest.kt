package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentNotificationRowPolicyTest {

    @Test
    fun `notificationRow joins fields and read flag with tabs`() {
        assertEquals(
            "n1\tMESSAGE\tNew message\tHi there\tread=false",
            AgentNotificationRowPolicy.notificationRow("n1", "MESSAGE", "New message", "Hi there", false),
        )
        assertEquals(
            "n2\tMISSED_CALL\tMissed call\t\tread=true",
            AgentNotificationRowPolicy.notificationRow("n2", "MISSED_CALL", "Missed call", "", true),
        )
    }
}
