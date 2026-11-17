package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentPinnedLinePolicyTest {

    @Test
    fun `pinnedLine joins messageId by and at with tabs`() {
        assertEquals(
            "m1\tby=u2\tat=1700000000000",
            AgentPinnedLinePolicy.pinnedLine("m1", "u2", 1700000000000L),
        )
    }
}
