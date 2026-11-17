package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentMissedCallLinePolicyTest {

    @Test
    fun `missedCallLine formats all fields tab separated`() {
        assertEquals(
            "m1\tu1\t小明\tVIDEO\t1700000000000\tread=false",
            AgentMissedCallLinePolicy.missedCallLine("m1", "u1", "小明", "VIDEO", 1700000000000, false),
        )
    }

    @Test
    fun `missedCallLine marks read calls`() {
        assertEquals(
            "m2\tu2\t小红\tAUDIO\t1700000001000\tread=true",
            AgentMissedCallLinePolicy.missedCallLine("m2", "u2", "小红", "AUDIO", 1700000001000, true),
        )
    }
}
